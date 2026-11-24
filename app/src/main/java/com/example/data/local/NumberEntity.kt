package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "numbers",
    indices = [Index(value = ["e164"], unique = true)]
)
data class NumberEntity(
    @PrimaryKey val id: String,
    val e164: String,
    val countryCode: String,
    val countryName: String,
    val label: String,
    val carrier: String,
    val providerType: String = PROVIDER_CUSTOM,
    val active: Boolean = true,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val PROVIDER_ANOSIM = "anosim"
        const val PROVIDER_RECEIVESMSS = "receivesmss"
        const val PROVIDER_CUSTOM = "custom"

        val DEFAULT_LIVE_NUMBERS: List<NumberEntity> = listOf(
            NumberEntity(
                id = "61485841378",
                e164 = "+61485841378",
                countryCode = "AU",
                countryName = "Australia",
                label = "Anosim",
                carrier = "Anosim",
                providerType = PROVIDER_ANOSIM,
            ),
            NumberEntity(
                id = "61468279945",
                e164 = "+61468279945",
                countryCode = "AU",
                countryName = "Australia",
                label = "RS 79945",
                carrier = "receive-smss",
                providerType = PROVIDER_RECEIVESMSS,
            ),
            NumberEntity(
                id = "61485922587",
                e164 = "+61485922587",
                countryCode = "AU",
                countryName = "Australia",
                label = "RS 22587",
                carrier = "receive-smss",
                providerType = PROVIDER_RECEIVESMSS,
            ),
            NumberEntity(
                id = "61483966924",
                e164 = "+61483966924",
                countryCode = "AU",
                countryName = "Australia",
                label = "RS 66924",
                carrier = "receive-smss",
                providerType = PROVIDER_RECEIVESMSS,
            ),
        )
    }
}
