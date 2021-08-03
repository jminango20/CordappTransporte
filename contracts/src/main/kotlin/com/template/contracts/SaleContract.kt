package com.template.contracts

import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction

class SaleContract : Contract {

    override fun verify(tx: LedgerTransaction) {
    }

}