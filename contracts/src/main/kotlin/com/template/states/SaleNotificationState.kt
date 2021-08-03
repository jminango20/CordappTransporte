package com.template.states

import com.template.contracts.SaleNotificationContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

@BelongsToContract(SaleNotificationContract::class)
data class SaleNotificationState(
    val retail: Party,
    val product: Product,

    override val participants: List<AbstractParty> = listOf(product.producer)
) : ContractState