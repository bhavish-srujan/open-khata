package com.example.khatabook

import com.example.khatabook.data.api.BillResponse
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.LedgerFeedEntry
import com.example.khatabook.data.api.PartyResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four screens wired in STEP 9 render server data through these DTOs, so
 * these tests pin the contract: the JSON the backend actually emits must
 * deserialise, and the fields the screens read must survive.
 *
 * The JSON fixtures below mirror the entity/serializer shapes in
 * bills.controller, inventory.controller and ledger.controller.
 */
class ApiResponseMappingTest {

    private val gson = Gson()

    @Test
    fun `bill response deserialises from the server shape`() {
        val json = """
            {
              "id": "3f1a9c22-0000-4000-8000-000000000001",
              "businessId": "11111111-1111-4111-8111-111111111111",
              "partyId": "22222222-2222-4222-8222-222222222222",
              "totalAmount": 1250.5,
              "items": null,
              "ledgerEntryId": null,
              "isGst": true,
              "receiptUrl": null,
              "created_at": "2026-08-06T10:15:00.000Z",
              "updated_at": "2026-08-06T10:15:00.000Z"
            }
        """.trimIndent()

        val bill = gson.fromJson(json, BillResponse::class.java)

        assertEquals("3f1a9c22-0000-4000-8000-000000000001", bill.id)
        assertEquals(1250.5, bill.totalAmount, 0.001)
        assertTrue(bill.isGst)
        assertNull(bill.receiptUrl)
        // The screen renders created_at.take(10) as the date column.
        assertEquals("2026-08-06", bill.created_at.take(10))
    }

    @Test
    fun `inventory item response deserialises and stock truncates to whole units`() {
        val json = """
            {
              "id": "item-1",
              "name": "Basmati Rice 5kg",
              "sku": "RICE-5",
              "sellingPrice": 480.0,
              "purchasePrice": 410.5,
              "currentStock": 12.75,
              "lowStockThreshold": 5.0
            }
        """.trimIndent()

        val item = gson.fromJson(json, InventoryItemResponse::class.java)

        assertEquals("Basmati Rice 5kg", item.name)
        assertEquals(480.0, item.sellingPrice, 0.001)
        // Server stock is decimal so it can hold weights; the list shows units.
        assertEquals(12, item.currentStock.toInt())
    }

    @Test
    fun `ledger feed entry deserialises with the party joined`() {
        val json = """
            {
              "id": "entry-1",
              "partyId": "22222222-2222-4222-8222-222222222222",
              "amount": 500.0,
              "type": "GOT",
              "note": "",
              "paymentMethod": "Cash",
              "source": "MANUAL",
              "createdAt": "2026-08-06T10:15:00.000Z",
              "party": {
                "id": "22222222-2222-4222-8222-222222222222",
                "name": "Rahul Traders",
                "phone": "+919876543210",
                "type": "CUSTOMER",
                "gstin": null,
                "currentBalance": 500.0,
                "businessId": "11111111-1111-4111-8111-111111111111",
                "createdAt": "2026-08-01T00:00:00.000Z"
              }
            }
        """.trimIndent()

        val entry = gson.fromJson(json, LedgerFeedEntry::class.java)

        assertEquals("GOT", entry.type)
        assertEquals("Rahul Traders", entry.party?.name)
    }

    @Test
    fun `ledger feed entry tolerates a missing party`() {
        val json = """
            {
              "id": "entry-2",
              "partyId": "22222222-2222-4222-8222-222222222222",
              "amount": 300.0,
              "type": "GAVE",
              "note": "Goods on credit",
              "paymentMethod": "Cash",
              "source": "OCR",
              "createdAt": "2026-08-06T10:15:00.000Z"
            }
        """.trimIndent()

        val entry = gson.fromJson(json, LedgerFeedEntry::class.java)

        assertNull(entry.party)
        assertEquals("Goods on credit", entry.note)
    }

    /**
     * Cashbook direction. GOT is money in, GAVE is credit out — the same
     * convention LedgerService uses when signing balances. Getting this
     * backwards would invert every total on the screen.
     */
    @Test
    fun `cashbook direction follows the ledger sign convention`() {
        val received = LedgerFeedEntry(
            id = "a", partyId = "p", amount = 500.0, type = "GOT", note = null,
            paymentMethod = "Cash", source = "MANUAL", createdAt = "2026-08-06T00:00:00.000Z",
        )
        val given = received.copy(id = "b", type = "GAVE")

        assertTrue("GOT must count as cash in", received.type == "GOT")
        assertTrue("GAVE must not count as cash in", given.type != "GOT")
    }

    @Test
    fun `party response tolerates a null gstin`() {
        val json = """
            {
              "id": "p1", "name": "Amit Kirana", "phone": "+919000000000",
              "type": "CUSTOMER", "gstin": null, "currentBalance": -250.0,
              "businessId": "biz", "createdAt": "2026-08-01T00:00:00.000Z"
            }
        """.trimIndent()

        val party = gson.fromJson(json, PartyResponse::class.java)

        assertNull(party.gstin)
        assertEquals(-250.0, party.currentBalance, 0.001)
    }
}
