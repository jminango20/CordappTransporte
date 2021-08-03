package com.template.states

import com.template.contracts.PurchaseOrderContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

@BelongsToContract(PurchaseOrderContract::class)
data class PurchaseOrderState(
        val buyer: Party,
        val seller: Party,
        val products: Map<Product.Type, Int>,
        val valueInCents: Int,

        override val participants: List<AbstractParty> = listOf(buyer,seller),
        override  val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState
