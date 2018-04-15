package it.menzani.cryptocharts

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.responses.AccountResponse
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

data class Setup(
        val localCurrency: LocalCurrency,
        val currenciesOwned: Array<CurrencyOwned>
)

data class LocalCurrency(
        val id: String,
        val languageTag: String
) {
    val formatter = CurrencyFormatter(Locale.forLanguageTag(languageTag))
}

data class CurrencyOwned(
        val id: String,
        val amount: Double,
        val stellarAccountId: String?
) {
    private val horizon by lazy {
        Network.usePublicNetwork()
        Server("https://horizon.stellar.org")
    }

    fun stellarAccount(): AccountResponse = horizon.accounts().account(KeyPair.fromAccountId(stellarAccountId!!))
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Currency(
        val symbol: String,
        val price_usd: String
) {
    private val priceFormatter = CurrencyFormatter(Locale.US)
    var netWorth = 0.0

    fun toString(formatter: CurrencyFormatter): String {
        val price_usd = priceFormatter.format(price_usd.toDouble())
        val netWorth = formatter.format(netWorth)
        return "$symbol/USD: $price_usd — $symbol Net Worth: $netWorth"
    }
}

class CurrencyFormatter(locale: Locale) {
    private val formatter: NumberFormat = DecimalFormat.getCurrencyInstance(locale)

    fun format(price: Double): String {
        val digits = when {
            price < 0.0001 -> 6
            price < 0.001 -> 5
            price < 0.01 -> 4
            price < 0.1 -> 3
            price < 10 -> 2
            price < 100 -> 1
            else -> 0
        }
        formatter.minimumFractionDigits = digits
        formatter.maximumFractionDigits = digits
        return formatter.format(price)
    }
}