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

    /** One row of the Cashbook's day-wise summary PDF. [runningBalance] is the
     *  cumulative total as of the end of that day, across the whole reported
     *  range — not just that day's own net. */
    data class DaySummaryRow(
        val dateLabel: String,
        val cashIn: Double,
        val cashOut: Double,
        val dailyBalance: Double,
        val runningBalance: Double,
    )

    /**
     * The Cashbook report's "Day-wise Summary Report" PDF — business name,
     * selected period, per-day totals and running balance, generated entirely
     * from real data the caller already fetched (the day-wise rows come from
     * [KhatabookRepository.dayWiseBreakdown] over the real, business-isolated
     * ledger feed; the overall totals come from the server's own
     * `/reports/cashflow`). Multi-page: unlike [generateAndShareLedgerReport]'s
     * single-page prototype, a report that would overflow one page continues
     * onto more pages rather than silently truncating.
     *
     * Returns the saved file (caller decides how to share it), or null if
     * there was nothing to generate a file from (I/O failure) — an empty
     * [rows] list is not itself a failure and still produces a valid
     * "no transactions in this period" PDF.
     */
    fun generateDayWiseSummaryPdf(
        context: Context,
        businessName: String,
        periodLabel: String,
        rangeLabel: String,
        rows: List<DaySummaryRow>,
        totalIn: Double,
        totalOut: Double,
    ): File? {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 50f
        val rowHeight = 26f

        val titlePaint = Paint().apply {
            textSize = 20f
            isFakeBoldText = true
            color = Color.rgb(21, 101, 192)
        }
        val headerPaint = Paint().apply {
            textSize = 12f
            color = Color.BLACK
            isFakeBoldText = true
        }
        val textPaint = Paint().apply {
            textSize = 11f
            color = Color.DKGRAY
        }
        val linePaint = Paint().apply { color = Color.LTGRAY }
        val inPaint = Paint(textPaint).apply { color = Color.rgb(34, 197, 94) }
        val outPaint = Paint(textPaint).apply { color = Color.RED }

        val pdfDocument = PdfDocument()
        var pageNumber = 1
        var page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 0f

        fun drawPageHeader(isFirstPage: Boolean) {
            y = 50f
            if (isFirstPage) {
                canvas.drawText("Day-wise Summary Report", margin, y, titlePaint)
                y += 30f
                canvas.drawText(businessName, margin, y, headerPaint)
                y += 20f
                canvas.drawText("Period: $periodLabel ($rangeLabel)", margin, y, textPaint)
                y += 18f
                val generatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
                canvas.drawText("Generated: $generatedAt", margin, y, textPaint)
                y += 30f
            }
            canvas.drawText("Date", margin, y, headerPaint)
            canvas.drawText("Cash In", margin + 130f, y, headerPaint)
            canvas.drawText("Cash Out", margin + 230f, y, headerPaint)
            canvas.drawText("Daily Balance", margin + 330f, y, headerPaint)
            canvas.drawText("Total Balance", margin + 430f, y, headerPaint)
            y += 14f
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 18f
        }

        fun newPage() {
            pdfDocument.finishPage(page)
            pageNumber += 1
            page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            drawPageHeader(isFirstPage = false)
        }

        drawPageHeader(isFirstPage = true)

        if (rows.isEmpty()) {
            canvas.drawText("No transactions in this period.", margin, y, textPaint)
            y += rowHeight
        } else {
            for (row in rows) {
                if (y > pageHeight - 60f) {
                    newPage()
                }
                canvas.drawText(row.dateLabel, margin, y, textPaint)
                canvas.drawText("+${row.cashIn.toInt()}", margin + 130f, y, inPaint)
                canvas.drawText("-${row.cashOut.toInt()}", margin + 230f, y, outPaint)
                canvas.drawText("${row.dailyBalance.toInt()}", margin + 330f, y, textPaint)
                canvas.drawText("${row.runningBalance.toInt()}", margin + 430f, y, textPaint)
                y += rowHeight
            }
        }

        if (y > pageHeight - 100f) newPage()
        y += 20f
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 24f
        canvas.drawText("Total Cash In: ₹${totalIn.toInt()}", margin, y, inPaint)
        y += 20f
        canvas.drawText("Total Cash Out: ₹${totalOut.toInt()}", margin, y, outPaint)
        y += 20f
        canvas.drawText("Net: ₹${(totalIn - totalOut).toInt()}", margin, y, headerPaint)

        pdfDocument.finishPage(page)

        val pdfsDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val file = File(pdfsDir, "DayWiseSummary_${System.currentTimeMillis()}.pdf")
        return try {
            pdfDocument.writeTo(FileOutputStream(file))
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            pdfDocument.close()
        }
    }

    /** Fires the same share-sheet flow the party-statement report uses, for
     *  any already-generated PDF [file]. */
    fun sharePdf(context: Context, file: File, subject: String) {
        val uri = FileProvider.getUriForFile(context, "com.example.khatabook.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share PDF using"))
    }
}
