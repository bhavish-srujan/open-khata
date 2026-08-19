package com.example.khatabook.data.local

import com.example.khatabook.data.api.BillResponse
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.LedgerFeedEntry
import com.example.khatabook.data.api.PartyResponse
import com.example.khatabook.data.api.StaffMemberResponse
import com.example.khatabook.data.local.dao.LedgerFeedRow
import com.example.khatabook.data.local.entity.BillEntity
import com.example.khatabook.data.local.entity.BusinessEntity
import com.example.khatabook.data.local.entity.ItemEntity
import com.example.khatabook.data.local.entity.LedgerEntryEntity
import com.example.khatabook.data.local.entity.PartyEntity
import com.example.khatabook.data.local.entity.PendingOpEntity
import com.example.khatabook.data.local.entity.StaffMemberEntity
import com.example.khatabook.data.model.BusinessAccount
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.EntrySource
import com.example.khatabook.data.model.EntryStatus
import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.LedgerEntry
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.model.TransactionType
import com.example.khatabook.data.repository.OpStatus
import com.example.khatabook.data.repository.PendingOp
import com.example.khatabook.data.repository.PendingOpType

/**
 * Domain-model <-> Room-entity conversions, in one place so
 * KhatabookRepository's business logic never has to know about column
 * layout. `ownerPhone`/`businessId` are stamped on the way *in* (the
 * domain models don't carry ownerPhone at all, and businessId is only
 * sometimes already on the domain model) rather than the way out — reads
 * always come back scoped by the DAO query itself.
 */

fun Party.toEntity(ownerPhone: String): PartyEntity = PartyEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId,
    name = name,
    phone = phone,
    type = type.name,
    collectionFrequency = collectionFrequency.name,
    address = address,
    gstin = gstin,
    flatBuilding = flatBuilding,
    areaLocality = areaLocality,
    pincode = pincode,
    city = city,
    state = state,
    isShippingSame = isShippingSame,
    currentBalance = currentBalance,
    openingBalance = openingBalance,
    creditLimit = creditLimit,
    tagsCsv = tags.joinToString(","),
    avatar = avatar,
    lastTransactionTime = lastTransactionTime,
    createdAt = createdAt,
    interestEnabled = interestEnabled,
    interestRate = interestRate,
    interestPeriodValue = interestPeriodValue,
    interestPeriodUnit = interestPeriodUnit.name,
)

fun PartyEntity.toDomain(): Party = Party(
    id = id,
    businessId = businessId,
    name = name,
    phone = phone,
    type = runCatching { PartyType.valueOf(type) }.getOrDefault(PartyType.CUSTOMER),
    collectionFrequency = runCatching { CollectionFrequency.valueOf(collectionFrequency) }.getOrDefault(CollectionFrequency.DAILY),
    address = address,
    gstin = gstin,
    flatBuilding = flatBuilding,
    areaLocality = areaLocality,
    pincode = pincode,
    city = city,
    state = state,
    isShippingSame = isShippingSame,
    currentBalance = currentBalance,
    openingBalance = openingBalance,
    creditLimit = creditLimit,
    tags = if (tagsCsv.isBlank()) emptyList() else tagsCsv.split(","),
    avatar = avatar,
    lastTransactionTime = lastTransactionTime,
    createdAt = createdAt,
    interestEnabled = interestEnabled,
    interestRate = interestRate,
    interestPeriodValue = interestPeriodValue,
    interestPeriodUnit = runCatching { InterestPeriodUnit.valueOf(interestPeriodUnit) }.getOrDefault(InterestPeriodUnit.DAYS),
)

/** Maps a server PartyResponse straight to an entity, for sync writes that
 *  never round-trip through the [Party] domain model. */
fun PartyResponse.toEntity(ownerPhone: String, resolvedBusinessId: String): PartyEntity = PartyEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId ?: resolvedBusinessId,
    name = name,
    phone = phone,
    type = if (type == "SUPPLIER") PartyType.SUPPLIER.name else PartyType.CUSTOMER.name,
    collectionFrequency = CollectionFrequency.DAILY.name,
    address = "",
    gstin = gstin ?: "",
    flatBuilding = "",
    areaLocality = "",
    pincode = "",
    city = "",
    state = "",
    isShippingSame = true,
    currentBalance = currentBalance,
    openingBalance = 0.0,
    creditLimit = 5000.0,
    tagsCsv = "",
    avatar = "",
    lastTransactionTime = System.currentTimeMillis(),
    createdAt = System.currentTimeMillis(),
    interestEnabled = interestEnabled ?: false,
    interestRate = interestRate ?: 0.0,
    interestPeriodValue = interestPeriodValue ?: 0,
    interestPeriodUnit = interestPeriodUnit ?: "DAYS",
)

fun LedgerEntry.toEntity(ownerPhone: String, businessId: String): LedgerEntryEntity = LedgerEntryEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId,
    partyId = partyId,
    amount = amount,
    type = type.name,
    note = note,
    paymentMethod = paymentMethod,
    source = source.name,
    status = status.name,
    imageUrl = imageUrl,
    rawExtractedText = rawExtractedText,
    createdAt = createdAt,
)

fun LedgerEntryEntity.toDomain(): LedgerEntry = LedgerEntry(
    id = id,
    businessId = businessId,
    partyId = partyId,
    amount = amount,
    type = runCatching { TransactionType.valueOf(type) }.getOrDefault(TransactionType.GAVE),
    note = note,
    paymentMethod = paymentMethod,
    source = runCatching { EntrySource.valueOf(source) }.getOrDefault(EntrySource.MANUAL),
    status = runCatching { EntryStatus.valueOf(status) }.getOrDefault(EntryStatus.CONFIRMED),
    imageUrl = imageUrl,
    rawExtractedText = rawExtractedText,
    createdAt = createdAt,
)

/**
 * The business-wide feed endpoint's own row shape, mapped straight to the
 * same [LedgerEntryEntity] table [LedgerEntry]/`getLedgerEntries` write to —
 * see [LedgerEntryEntity]'s class doc for why there is only one table.
 *
 * `createdAt` defaults to "now" rather than parsing the server's ISO
 * timestamp — matching the pre-existing behavior of the per-party sync path
 * (`syncLedgerEntriesFromServer`'s mapped [LedgerEntry] never set it from
 * the server either), preserved here rather than fixed, since this
 * migration's scope is the storage engine, not this pre-existing quirk.
 */
fun LedgerFeedEntry.toEntity(ownerPhone: String, businessId: String): LedgerEntryEntity = LedgerEntryEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId,
    partyId = partyId,
    amount = amount,
    type = type,
    note = note ?: "",
    paymentMethod = paymentMethod,
    source = source,
    status = EntryStatus.CONFIRMED.name,
    imageUrl = null,
    rawExtractedText = null,
    createdAt = System.currentTimeMillis(),
)

fun LedgerFeedRow.toDomain(): LedgerFeedEntry = LedgerFeedEntry(
    id = entry.id,
    partyId = entry.partyId,
    amount = entry.amount,
    type = entry.type,
    note = entry.note,
    paymentMethod = entry.paymentMethod,
    source = entry.source,
    createdAt = entry.createdAt.toString(),
    party = if (partyName == null) null else PartyResponse(
        id = entry.partyId,
        name = partyName,
        phone = partyPhone ?: "",
        type = "",
        gstin = null,
        currentBalance = 0.0,
        businessId = entry.businessId,
        createdAt = "",
    ),
)

fun BusinessAccount.toEntity(ownerPhone: String): BusinessEntity = BusinessEntity(
    id = id,
    ownerPhone = ownerPhone,
    name = name,
    type = type,
    isPrimary = isPrimary,
    gstNumber = gstNumber,
    address = address,
    category = category,
    bankAccount = bankAccount,
    profileImageUrl = profileImageUrl,
    role = role,
)

fun BusinessEntity.toDomain(): BusinessAccount = BusinessAccount(
    id = id,
    name = name,
    type = type,
    isPrimary = isPrimary,
    gstNumber = gstNumber,
    address = address,
    category = category,
    bankAccount = bankAccount,
    profileImageUrl = profileImageUrl,
    role = role,
)

fun InventoryItemResponse.toEntity(ownerPhone: String, businessId: String): ItemEntity = ItemEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId,
    name = name,
    sku = sku,
    sellingPrice = sellingPrice,
    purchasePrice = purchasePrice,
    currentStock = currentStock,
    lowStockThreshold = lowStockThreshold,
)

fun ItemEntity.toDomain(): InventoryItemResponse = InventoryItemResponse(
    id = id,
    name = name,
    sku = sku,
    sellingPrice = sellingPrice,
    purchasePrice = purchasePrice,
    currentStock = currentStock,
    lowStockThreshold = lowStockThreshold,
)

fun StaffMemberResponse.toEntity(ownerPhone: String, businessId: String): StaffMemberEntity = StaffMemberEntity(
    membershipId = membershipId,
    ownerPhone = ownerPhone,
    businessId = businessId,
    userId = userId,
    name = name,
    phoneNumber = phoneNumber,
    role = role,
    addedAt = addedAt,
)

fun StaffMemberEntity.toDomain(): StaffMemberResponse = StaffMemberResponse(
    membershipId = membershipId,
    userId = userId,
    name = name,
    phoneNumber = phoneNumber,
    role = role,
    addedAt = addedAt,
)

fun BillResponse.toEntity(ownerPhone: String, businessId: String): BillEntity = BillEntity(
    id = id,
    ownerPhone = ownerPhone,
    businessId = businessId,
    partyId = partyId,
    totalAmount = totalAmount,
    isGst = isGst,
    receiptUrl = receiptUrl,
    createdAt = created_at,
)

fun BillEntity.toDomain(): BillResponse = BillResponse(
    id = id,
    partyId = partyId,
    totalAmount = totalAmount,
    isGst = isGst,
    receiptUrl = receiptUrl,
    created_at = createdAt,
)

fun PendingOp.toEntity(ownerPhone: String): PendingOpEntity = PendingOpEntity(
    opId = opId,
    type = type.name,
    targetId = targetId,
    payload = payload,
    attempts = attempts,
    ownerPhone = ownerPhone,
    businessId = businessId,
    status = status.name,
    rollbackPayload = rollbackPayload,
)

fun PendingOpEntity.toDomain(): PendingOp = PendingOp(
    opId = opId,
    type = PendingOpType.valueOf(type),
    targetId = targetId,
    payload = payload,
    attempts = attempts,
    businessId = businessId,
    status = OpStatus.valueOf(status),
    rollbackPayload = rollbackPayload,
)
