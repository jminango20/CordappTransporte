package com.template.states

import com.template.contracts.SaleContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

@BelongsToContract(SaleContract::class)
data class SaleState(
    val retail: Party,
    val product: Product,
    val price : Int,

    override val participants: List<AbstractParty> = listOf(retail)
) : ContractState