// Prints the text of a PDF (macOS PDFKit). Used by ui_flow.py to check report contents.
import Foundation
import PDFKit

guard CommandLine.arguments.count > 1, let doc = PDFDocument(url: URL(fileURLWithPath: CommandLine.arguments[1])) else {
    FileHandle.standardError.write("usage: pdf_text.swift <file.pdf>\n".data(using: .utf8)!)
    exit(1)
}
print(doc.string ?? "")
