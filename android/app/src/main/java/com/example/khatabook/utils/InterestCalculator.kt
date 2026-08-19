package com.example.khatabook.utils

import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.Party

/**
 * Pure, non-annualized interest calculation: [Party.interestRate] applies
 * exactly once to [principal], for whatever period the party was configured
 * with — never converted to a daily/monthly/yearly rate, and never applied
 * more than once regardless of how long that period actually spans. "5% /
 * 100 Days" on ₹10,000 is always ₹500, whether 10 days or 400 days have
 * passed; this function has no notion of elapsed time at all, deliberately —
 * the period value/unit are the user's own configured statement of *when*
 * that flat amount is due, not a rate to be prorated.
 *
 * Returns 0.0 whenever interest isn't configured, so a caller can apply this
 * unconditionally to any party without checking [Party.interestEnabled]
 * first. Never writes back to any balance or ledger entry — purely a
 * display-time computation.
 */
object InterestCalculator {
    fun calculate(party: Party, principal: Double): Double {
        if (!party.interestEnabled || party.interestRate <= 0.0 || principal <= 0.0) return 0.0
        return principal * (party.interestRate / 100.0)
    }

    /** e.g. "5% / 100 Days" — the configured rate/period, independent of any
     *  particular principal or computed amount. */
    fun describePeriod(party: Party): String {
        val unit = when (party.interestPeriodUnit) {
            InterestPeriodUnit.DAYS -> if (party.interestPeriodValue == 1) "Day" else "Days"
            InterestPeriodUnit.MONTHS -> if (party.interestPeriodValue == 1) "Month" else "Months"
        }
        return "${formatRate(party.interestRate)}% / ${party.interestPeriodValue} $unit"
    }

    private fun formatRate(rate: Double): String =
        if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()
}
