package com.example.khatabook

import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.Party
import com.example.khatabook.utils.InterestCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class InterestCalculatorTest {

    private fun party(
        interestEnabled: Boolean = true,
        interestRate: Double = 5.0,
        interestPeriodValue: Int = 100,
        interestPeriodUnit: InterestPeriodUnit = InterestPeriodUnit.DAYS
    ) = Party(
        name = "Test",
        phone = "9876543210",
        interestEnabled = interestEnabled,
        interestRate = interestRate,
        interestPeriodValue = interestPeriodValue,
        interestPeriodUnit = interestPeriodUnit
    )

    @Test
    fun `flat rate applies once to principal, not annualized or prorated`() {
        // 5% / 100 Days on 10,000 is always 500 — the calculator has no
        // notion of elapsed time, so this is identical whether 10 days or
        // 400 days have actually passed.
        assertEquals(500.0, InterestCalculator.calculate(party(), 10000.0), 0.0001)
    }

    @Test
    fun `disabled interest yields zero regardless of stored rate`() {
        assertEquals(0.0, InterestCalculator.calculate(party(interestEnabled = false), 10000.0), 0.0001)
    }

    @Test
    fun `zero or negative principal yields zero`() {
        assertEquals(0.0, InterestCalculator.calculate(party(), 0.0), 0.0001)
        assertEquals(0.0, InterestCalculator.calculate(party(), -500.0), 0.0001)
    }

    @Test
    fun `months period is never converted to days`() {
        val monthly = party(interestRate = 2.0, interestPeriodValue = 3, interestPeriodUnit = InterestPeriodUnit.MONTHS)
        assertEquals(200.0, InterestCalculator.calculate(monthly, 10000.0), 0.0001)
        assertEquals("2% / 3 Months", InterestCalculator.describePeriod(monthly))
    }

    @Test
    fun `describePeriod formats whole and fractional rates and singular units`() {
        assertEquals("5% / 100 Days", InterestCalculator.describePeriod(party()))
        assertEquals(
            "1.5% / 1 Day",
            InterestCalculator.describePeriod(party(interestRate = 1.5, interestPeriodValue = 1))
        )
        assertEquals(
            "10% / 1 Month",
            InterestCalculator.describePeriod(party(interestRate = 10.0, interestPeriodValue = 1, interestPeriodUnit = InterestPeriodUnit.MONTHS))
        )
    }
}
