package app.minimpos.app.data.db

import androidx.room.TypeConverter
import app.minimpos.core.money.PaymentContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** Room's text representation of the non-secret payment context. */
class PaymentContextConverter {
    /** Encodes [context]; null means no request has been sent. */
    @TypeConverter
    fun encode(context: PaymentContext?): String? = context?.let { Json.encodeToString(serializer<PaymentContext>(), it) }

    /** Decodes [text]; null means no request has been sent. */
    @TypeConverter
    fun decode(text: String?): PaymentContext? = text?.let { Json.decodeFromString(serializer<PaymentContext>(), it) }
}
