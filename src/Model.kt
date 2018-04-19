package it.menzani.cryptocharts

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.responses.AccountResponse
import java.net.URL
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.Callable

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

class Fetcher(private val suppressed: Exception?, private val setup: Setup?, private val mapper: ObjectMapper) : Callable<Fetcher.Result> {
    override fun call(): Result {
        if (suppressed != null) throw suppressed

        val currencies: MutableList<Currency> = mutableListOf()
        for (currencyOwned in setup!!.currenciesOwned) {
            val tree = fetch(currencyOwned.id, setup.localCurrency.id.toUpperCase())
            val currency: Currency = mapper.treeToValue(tree)
            val amount = if (currencyOwned.id == "stellar" && currencyOwned.stellarAccountId != null) {
                Arrays.stream(currencyOwned.stellarAccount().balances)
                        .filter { it.assetType == "native" }
                        .findFirst().get()
                        .balance.toDouble()
            } else {
                currencyOwned.amount
            }
            val price = tree["price_${setup.localCurrency.id.toLowerCase()}"].asDouble()
            currency.netWorth = amount * price
            currencies.add(currency)
        }
        return Result(currencies, setup.localCurrency)
    }

    private fun fetch(currencyId: String, localCurrencyId: String): JsonNode {
        val response: JsonNode = mapper.readTree(URL(
                "https://api.coinmarketcap.com/v1/ticker/$currencyId/?convert=$localCurrencyId"))
        if (response.isArray && response.size() == 1) {
            return response[0]
        }
        throw Exception("Unexpected response: $response")
    }

    class Result(val currencies: List<Currency>, val localCurrency: LocalCurrency)
}