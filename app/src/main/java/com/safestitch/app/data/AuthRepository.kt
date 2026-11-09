package com.safestitch.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.core.content.edit
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.PasswordHasher
import com.safestitch.app.domain.UserRole
import com.safestitch.app.domain.Usernames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AuthState {
    data object Loading : AuthState
    /** No accounts exist yet: the first person creates the admin account. */
    data object NeedsSetup : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: UserEntity) : AuthState
}

/**
 * Local admin/inspector accounts (works offline). Passwords are stored only as salted PBKDF2 hashes.
 * The signed-in user is remembered on this phone until they sign out.
 * Every method that changes data returns an error message, or null on success.
 */
class AuthRepository(
    context: Context,
    database: SafeStitchDatabase,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    prefsName: String = "safestitch_session",
) {
    private val users = database.userDao()
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private var failedAttempts = 0
    private var lockedUntil = 0L

    init {
        scope.launch { refresh() }
    }

    val currentUser: UserEntity? get() = (state.value as? AuthState.SignedIn)?.user

    fun observeUsers(): Flow<List<UserEntity>> = users.observeAll()

    suspend fun refresh() {
        mutableState.value = if (users.count() == 0) {
            AuthState.NeedsSetup
        } else {
            val id = prefs.getLong(KEY_USER, -1L)
            val user = if (id > 0) users.getById(id) else null
            if (user != null && user.active) AuthState.SignedIn(user) else AuthState.SignedOut
        }
    }

    suspend fun createFirstAdmin(displayName: String, username: String, password: String): String? {
        if (users.count() > 0) return "An admin account already exists. Sign in instead."
        val error = insertUser(displayName, username, UserRole.ADMIN, password)
        if (error != null) return error
        return signIn(username, password)
    }

    suspend fun createUser(admin: UserEntity, displayName: String, username: String, role: UserRole, password: String): String? {
        if (!admin.isAdmin) return "Only an admin can add users."
        return insertUser(displayName, username, role, password)
    }

    suspend fun signIn(username: String, password: String): String? {
        val now = clock()
        if (now < lockedUntil) return "Too many wrong attempts. Try again in ${(lockedUntil - now + 999) / 1000} seconds."
        val user = users.findByUsername(Usernames.normalize(username))
        if (user == null || !withContext(Dispatchers.Default) { PasswordHasher.verify(password, user.passwordHash) }) {
            failedAttempts += 1
            if (failedAttempts >= MAX_ATTEMPTS) {
                lockedUntil = now + LOCK_MILLIS
                failedAttempts = 0
            }
            return "Wrong username or password."
        }
        if (!user.active) return "This account is deactivated. Ask an admin."
        failedAttempts = 0
        prefs.edit { putLong(KEY_USER, user.id) }
        mutableState.value = AuthState.SignedIn(user)
        return null
    }

    fun signOut() {
        prefs.edit { remove(KEY_USER) }
        mutableState.value = AuthState.SignedOut
    }

    suspend fun changePassword(user: UserEntity, current: String, new: String): String? {
        val stored = users.getById(user.id) ?: return "Account not found."
        if (!withContext(Dispatchers.Default) { PasswordHasher.verify(current, stored.passwordHash) }) return "The current password is wrong."
        PasswordHasher.problem(new)?.let { return it }
        users.update(stored.copy(passwordHash = hash(new)))
        return null
    }

    suspend fun resetPassword(admin: UserEntity, userId: Long, new: String): String? {
        if (!admin.isAdmin) return "Only an admin can reset passwords."
        PasswordHasher.problem(new)?.let { return it }
        val target = users.getById(userId) ?: return "Account not found."
        users.update(target.copy(passwordHash = hash(new)))
        return null
    }

    suspend fun setActive(admin: UserEntity, userId: Long, active: Boolean): String? {
        if (!admin.isAdmin) return "Only an admin can change accounts."
        val target = users.getById(userId) ?: return "Account not found."
        if (!active && target.id == admin.id) return "You cannot deactivate your own account."
        if (!active && target.isAdmin && users.countActiveAdmins() <= 1) return "Keep at least one active admin."
        users.update(target.copy(active = active))
        return null
    }

    private suspend fun insertUser(displayName: String, username: String, role: UserRole, password: String): String? {
        if (displayName.isBlank()) return "Enter the person's name."
        Usernames.problem(username)?.let { return it }
        PasswordHasher.problem(password)?.let { return it }
        val normalized = Usernames.normalize(username)
        if (users.findByUsername(normalized) != null) return "The username \"$normalized\" is already taken."
        return try {
            users.insert(
                UserEntity(
                    username = normalized,
                    displayName = displayName.trim(),
                    role = role,
                    passwordHash = hash(password),
                    createdAt = clock(),
                ),
            )
            null
        } catch (_: SQLiteConstraintException) {
            "The username \"$normalized\" is already taken."
        }
    }

    private suspend fun hash(password: String): String = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }

    private companion object {
        const val KEY_USER = "signed_in_user_id"
        const val MAX_ATTEMPTS = 5
        const val LOCK_MILLIS = 30_000L
    }
}
