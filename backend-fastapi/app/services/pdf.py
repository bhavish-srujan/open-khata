import io
from datetime import datetime
from decimal import Decimal
from typing import Any, List, Optional

from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas

from app.models.ledger import LedgerEntry
from app.models.party import Party


def generate_business_statement_pdf(
    business_name: str,
    party: Optional[Party],
    entries: List[LedgerEntry],
    start_date: datetime,
    end_date: datetime,
) -> bytes:
    buffer = io.BytesIO()
    c = canvas.Canvas(buffer, pagesize=letter)
    width, height = letter

    # Margins and layout
    margin_x = 50
    y = height - 50

    # Header
    c.setFont("Helvetica-Bold", 20)
    c.drawCentredString(width / 2.0, y, business_name or "Open Khata Business")
    y -= 25

    c.setFont("Helvetica", 14)
    c.drawCentredString(width / 2.0, y, "Business Statement")
    y -= 30

    c.setFont("Helvetica", 11)
    if party:
        c.drawString(
            margin_x,
            y,
            f"Customer/Supplier: {party.name} ({party.phone or 'N/A'})",
        )
        y -= 18

    start_str = start_date.strftime("%d/%m/%Y")
    end_str = end_date.strftime("%d/%m/%Y")
    c.drawString(margin_x, y, f"Period: {start_str} - {end_str}")
    y -= 30

    # Table Header
    c.setFont("Helvetica-Bold", 10)
    c.drawString(50, y, "Date")
    c.drawString(140, y, "Type")
    c.drawString(230, y, "Amount")
    c.drawString(320, y, "Note")
    c.drawString(450, y, "Status")
    y -= 5

    c.setLineWidth(1)
    c.line(50, y, 550, y)
    y -= 15

    total_gave = Decimal("0.00")
    total_got = Decimal("0.00")

    c.setFont("Helvetica", 9)
    for entry in entries:
        if y < 80:
            c.showPage()
            y = height - 50
            c.setFont("Helvetica", 9)

        entry_date = (
            entry.transaction_date.strftime("%d/%m/%Y")
            if entry.transaction_date
            else entry.created_at.strftime("%d/%m/%Y")
        )
        type_str = entry.type
        amount_str = f"Rs {entry.amount}"
        note_str = (entry.note or "-")[:25]
        status_str = entry.status or "CONFIRMED"

        if entry.type == "GAVE":
            total_gave += entry.amount
        elif entry.type == "GOT":
            total_got += entry.amount

        c.drawString(50, y, entry_date)
        c.drawString(140, y, type_str)
        c.drawString(230, y, amount_str)
        c.drawString(320, y, note_str)
        c.drawString(450, y, status_str)
        y -= 18

    # Separator
    y -= 5
    c.line(50, y, 550, y)
    y -= 25

    # Summary section
    if y < 100:
        c.showPage()
        y = height - 50

    c.setFont("Helvetica-Bold", 12)
    c.drawString(50, y, "Summary")
    y -= 18

    c.setFont("Helvetica", 10)
    c.drawString(50, y, f"Total GAVE: Rs {total_gave:.2f}")
    y -= 15
    c.drawString(50, y, f"Total GOT: Rs {total_got:.2f}")
    y -= 15

    net = total_got - total_gave
    status_label = "(Positive)" if net >= 0 else "(Negative)"
    c.setFont("Helvetica-Bold", 10)
    c.drawString(50, y, f"Net: Rs {abs(net):.2f} {status_label}")

    c.save()
    buffer.seek(0)
    return buffer.getvalue()
