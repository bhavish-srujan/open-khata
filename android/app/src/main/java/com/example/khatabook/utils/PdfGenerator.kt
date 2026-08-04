package com.example.khatabook.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.TransactionType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {
    fun generateAndShareLedgerReport(
        context: Context,
        party: Party,
        entries: List<LedgerEntry>
    ) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Size: 595 x 842 points
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val paint = Paint()
        val titlePaint = Paint().apply {
            textSize = 24f
            isFakeBoldText = true
            color = Color.rgb(21, 101, 192) // KhatabookHeaderBlue
        }
        val headerPaint = Paint().apply {
            textSize = 14f
            color = Color.BLACK
            isFakeBoldText = true
        }
        val textPaint = Paint().apply {
            textSize = 12f
            color = Color.DKGRAY
        }

        var yPosition = 50f
        val margin = 50f

        // Title
        canvas.drawText("Ledger Statement", margin, yPosition, titlePaint)
        yPosition += 40f

        // Customer Info
        canvas.drawText("Customer: ${party.name}", margin, yPosition, headerPaint)
        yPosition += 20f
        canvas.drawText("Phone: ${party.phone}", margin, yPosition, textPaint)
        yPosition += 20f
        val netBalanceText = "Net Balance: ₹${kotlin.math.abs(party.currentBalance)}" +
                if (party.currentBalance < 0) " (You Give)" else " (You Got)"
        canvas.drawText(netBalanceText, margin, yPosition, headerPaint)
        yPosition += 40f

        // Table Headers
        canvas.drawText("Date", margin, yPosition, headerPaint)
        canvas.drawText("Details", margin + 140f, yPosition, headerPaint)
        canvas.drawText("You Gave", margin + 340f, yPosition, headerPaint)
        canvas.drawText("You Got", margin + 440f, yPosition, headerPaint)
        yPosition += 20f

        canvas.drawLine(margin, yPosition - 15f, pageInfo.pageWidth - margin, yPosition - 15f, paint)

        // Table Rows
        val dateFormat = SimpleDateFormat("dd MMM yy, hh:mm a", Locale.getDefault())
        for (entry in entries) {
            if (yPosition > pageInfo.pageHeight - 50f) {
                // Not handling multi-page for this simple prototype
                canvas.drawText("... more entries truncated ...", margin, yPosition, textPaint)
                break
            }

            val dateStr = dateFormat.format(Date(entry.createdAt))
            canvas.drawText(dateStr, margin, yPosition, textPaint)
            
            // Truncate note if too long
            val note = if (entry.note.length > 20) entry.note.take(20) + "..." else entry.note
            canvas.drawText(note, margin + 140f, yPosition, textPaint)

            if (entry.type == TransactionType.GAVE) {
                canvas.drawText("₹${entry.amount.toInt()}", margin + 340f, yPosition, Paint(textPaint).apply { color = Color.RED })
            } else {
                canvas.drawText("₹${entry.amount.toInt()}", margin + 440f, yPosition, Paint(textPaint).apply { color = Color.rgb(34, 197, 94) })
            }
            yPosition += 30f
        }

        pdfDocument.finishPage(page)

        // Save file
        val pdfsDir = File(context.cacheDir, "pdfs")
        if (!pdfsDir.exists()) {
            pdfsDir.mkdirs()
        }
        val file = File(pdfsDir, "Statement_${party.name.replace(" ", "_")}.pdf")
        try {
            pdfDocument.writeTo(FileOutputStream(file))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pdfDocument.close()

        // Share Intent
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(context, "com.example.khatabook.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Ledger Statement for ${party.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share PDF using"))
        }
    }
}
