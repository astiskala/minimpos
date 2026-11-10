package app.minimpos.app.data.db

import androidx.room.TypeConverter
import app.minimpos.core.payment.PaymentContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** Room's text representation of the non-secret payment context. */
class PaymentContextConverter {
    /** Encodes [context]; null means no request has been sent. */
    @TypeConverter
    fun encode(context: PaymentContext?): String? = context?.let { Json.encodeToString(serializer<PaymentContext>(), it) }

    /**
     * Decodes [text]; null means no request has been sent. Fields this version does not know, such as those a newer
     * version wrote before a downgrade, are ignored. Damaged text still fails: reading it as no context would let a
     * historical operation skip its original-context check.
     */
    @TypeConverter
    fun decode(text: String?): PaymentContext? = text?.let { LENIENT.decodeFromString(serializer<PaymentContext>(), it) }

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
