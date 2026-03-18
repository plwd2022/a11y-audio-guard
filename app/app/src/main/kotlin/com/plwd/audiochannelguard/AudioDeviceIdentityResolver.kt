package com.plwd.audiochannelguard

internal object AudioDeviceIdentityResolver {
    fun identityKey(
        type: Int,
        address: String?,
        productName: String?,
    ): String {
        val normalizedAddress = address.orEmpty()
        if (normalizedAddress.isNotEmpty()) {
            return "$type:$normalizedAddress"
        }

        val normalizedProductName = productName.orEmpty()
        if (normalizedProductName.isNotEmpty()) {
            return "$type:$normalizedProductName"
        }

        return type.toString()
    }

    fun isSamePhysicalDevice(
        firstAddress: String?,
        firstProductName: String?,
        secondAddress: String?,
        secondProductName: String?,
    ): Boolean {
        val normalizedFirstAddress = firstAddress.orEmpty()
        val normalizedSecondAddress = secondAddress.orEmpty()
        if (normalizedFirstAddress.isNotEmpty() && normalizedFirstAddress == normalizedSecondAddress) {
            return true
        }

        return firstProductName == secondProductName
    }
}
