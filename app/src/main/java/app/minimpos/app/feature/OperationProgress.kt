package app.minimpos.app.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import app.minimpos.app.payment.TransactionState

/**
 * Follows the financial operation a waiting screen shows ([app.minimpos.app.payment.FinancialOperations.paymentProgress]
 * or `refundProgress`): once it finished, [onFinish] opens its result with the stored record's ID; when nothing runs
 * or waits to be seen ([TransactionState.Idle]), [onIdle] leaves. The one reading of how waiting screens move on.
 */
@Composable
fun FollowProgress(
    state: TransactionState,
    onFinish: (id: String) -> Unit,
    onIdle: () -> Unit = {},
) {
    val finish by rememberUpdatedState(onFinish)
    val idle by rememberUpdatedState(onIdle)
    LaunchedEffect(state) {
        when (state) {
            is TransactionState.Finished -> finish(state.id)
            TransactionState.Idle -> idle()
            is TransactionState.Processing -> Unit
        }
    }
}
