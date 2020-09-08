package it.menzani.cryptocharts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.stellar.sdk.Server
import org.stellar.sdk.responses.AccountResponse
import java.net.URL
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.Callable
import java.util.stream.Collectors

data class Setup(
        val localCurrency: LocalCurrency,
        val currenciesOwned: Array<CurrencyOwned>
)

data class LocalCurrency(
        val id: String,
        val languageTag: String
) {
    val priceFormatter = CurrencyFormatter(Locale.forLanguageTag(languageTag))
}

data class CurrencyOwned(
        val id: String,
        val amount: Double,
        val stellarAccountId: String?
) {
    private val horizon by lazy {
        Server("https://horizon.stellar.org")
    }

    fun stellarAccount(): AccountResponse = horizon.accounts().account(stellarAccountId!!)
}

class Currency(private val symbol: String, private val globalPrice: Double, val netWorth: Double) {
    private val globalPriceFormatter = CurrencyFormatter(Locale.US)

    fun toString(localPriceFormatter: CurrencyFormatter): String {
        val globalPrice = globalPriceFormatter.format(globalPrice)
        val netWorth = localPriceFormatter.format(netWorth)
        return "$symbol/USD: $globalPrice — $symbol Net Worth: $netWorth"
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

        val fromSymbols = Arrays.stream(setup!!.currenciesOwned)
                .map { it.id }
                .collect(Collectors.joining(","))
        val toSymbols = "USD," + setup.localCurrency.id
        val data = fetch(fromSymbols, toSymbols)

        val currencies: MutableList<Currency> = mutableListOf()
        for (currencyOwned in setup.currenciesOwned) {
            val currencyData = data[currencyOwned.id]
            val amount = if (currencyOwned.id == "XLM" && currencyOwned.stellarAccountId != null) {
                Arrays.stream(currencyOwned.stellarAccount().balances)
                        .filter { it.assetType == "native" }
                        .findFirst().get()
                        .balance.toDouble()
            } else {
                currencyOwned.amount
            }
            val localPrice = currencyData[setup.localCurrency.id].asDouble()
            val currency = Currency(currencyOwned.id, currencyData["USD"].asDouble(), amount * localPrice)
            currencies.add(currency)
        }
        return Result(currencies, setup.localCurrency.priceFormatter)
    }

    private fun fetch(fromSymbols: String, toSymbols: String): JsonNode {
        val response: JsonNode = mapper.readTree(URL(
                "https://min-api.cryptocompare.com/data/pricemulti?fsyms=$fromSymbols&tsyms=$toSymbols"))
        if (response.isObject) {
            return response
        }
        throw Exception("Unexpected response: $response")
    }

    class Result(val currencies: List<Currency>, val localPriceFormatter: CurrencyFormatter)
}