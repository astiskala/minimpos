package app.minimpos.terminal.checkout

import com.adyen.model.applicationinfo.ApplicationInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * Adyen's Checkout API for one credential, as the app uses it: modifications (captures, adjustments, refunds and access
 * checks), payment links and saved cards. Made once per [CheckoutCredentials] by [AdyenCheckout], or by the simulator;
 * every part is safe from any thread and reports network and API errors as results, never thrown.
 */
interface CheckoutApi {
    /** Captures, adjustments, refunds and access checks. */
    val modifications: PaymentModifications

    /** Payment links. */
    val links: PaymentLinkApi

    /** Saved cards: listing a shopper's cards and charging one. */
    val savedCards: SavedCardApi

    /** Assembling one from parts. */
    companion object {
        /** A [CheckoutApi] of [modifications], [links] and [savedCards], such as the simulator's or a test's fakes. */
        fun of(
            modifications: PaymentModifications,
            links: PaymentLinkApi,
            savedCards: SavedCardApi,
        ): CheckoutApi = Parts(modifications, links, savedCards)
    }

    private class Parts(
        override val modifications: PaymentModifications,
        override val links: PaymentLinkApi,
        override val savedCards: SavedCardApi,
    ) : CheckoutApi
}

/**
 * [CheckoutApi] over HTTPS for [credentials]: its three parts share one HTTP client and connection pool, the API key and
 * the application information sent with every payment and link.
 *
 * @param credentials The merchant account, environment and API key.
 * @param application Application information sent with charges, links and modifications; null sends none.
 * @param baseClient The client the parts derive from.
 * @param dispatcher Where the blocking calls run.
 */
class AdyenCheckout(
    credentials: CheckoutCredentials,
    application: ApplicationInfo? = null,
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CheckoutApi {
    override val modifications: PaymentModifications =
        CheckoutModifications(credentials, baseClient = baseClient, dispatcher = dispatcher, application = application)

    override val links: PaymentLinkApi =
        CheckoutPaymentLinks(credentials, baseClient = baseClient, dispatcher = dispatcher, application = application)

    override val savedCards: SavedCardApi =
        CheckoutSavedCards(credentials, baseClient = baseClient, dispatcher = dispatcher, application = application)
}
