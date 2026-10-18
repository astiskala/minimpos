package app.minimpos.terminal

/** Adyen decimals for the currencies the tests use: none for JPY, two for the others. */
internal val testCurrencyDigits: (String) -> Int? = { currency -> if (currency == "JPY") 0 else 2 }
