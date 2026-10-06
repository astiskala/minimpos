---
name: minimpos-tests
description: Add Mini mPOS tests using existing plain JUnit, Robolectric, Compose and fake transport patterns.
---

# Mini mPOS test starters

[Module rules](../../../app/AGENTS.md#tests) own lifecycle and synchronization pitfalls;
[CONTRIBUTING](../../../CONTRIBUTING.md#test-setup) owns test setup. Copy the smallest applicable pattern,
rename it, replace sample behavior with the regression, observe failure, fix production, rerun.
Do not add a second fixture framework or placeholders to checked-in tests.

## Pure decision: plain JUnit

Use for money, formats and decision owners without Android calls, including pure app rules.
Nearest examples: `core/.../MoneyTest.kt`, `app/.../payment/CheckoutTest.kt`.

```kotlin
package io.github.astiskala.minimpos.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurrencyDecisionTest {
    @Test
    fun `ISK uses Adyen minor units`() {
        assertThat(CurrencySpec.of("ISK").fractionDigits).isEqualTo(2)
    }
}
```

Run `scripts/dev test core '*CurrencyDecisionTest'`.

## Repository: Robolectric and temporary environment

Nearest example: `app/.../data/RepositoriesTest.kt`. No manually constructed production container or real network.

```kotlin
package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogSeedTest {
    @get:Rule
    val env = TestEnvironment()

    @Test
    fun `starter rates seed once`() = await {
        val catalog = env.container.catalog
        catalog.seedDefaults(listOf(TaxRateEntity(name = "Zero", rateMilliPercent = 0)))
        catalog.seedDefaults(listOf(TaxRateEntity(name = "Other", rateMilliPercent = 5_000)))
        assertThat(catalog.taxRates.first().map { it.name }).containsExactly("Zero")
    }
}
```

Run `scripts/dev test app '*CatalogSeedTest'`.
For view models, adapt `OnboardingViewModelTest`'s tracked view-model cleanup and Main dispatcher lifecycle.
For startup tests, use the same `UnconfinedTestDispatcher` in `TestEnvironment` and `runTest`, suspend for state
changes; do not replace DataStore synchronization with sleeps or blind `advanceUntilIdle` calls.

## Compose: environment outside recording rule

Nearest examples: `AppFlowTest`, `SmallScreenTest`, `LocalizedUiTest`. Test wiring/layout here; decisions in JUnit.

```kotlin
package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.createRecordingComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class HomeLayoutTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `sale action fits AMS1`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("newSale").assertIsDisplayed()
    }
}
```

Run `scripts/dev test app '*HomeLayoutTest'`. Use `compose.awaitCondition` for async state; wait for editor closure,
not only saved text. Recording runs inside Compose's lifecycle; passing tests do not capture. Failure directories
are unique across worker JVMs. Metadata identifies test, locale, dimensions and capture time; inspect each root's
PNG and unmerged tree. Capture failures retain the original assertion.

## Transport uncertainty: existing fake and deterministic identity

Adapt `adyen/.../TerminalClientTest.kt`'s `client`, fixed clock, deterministic service IDs, `sent` list and
`TerminalTransport` lambda. Return typed `Delivery` values, not thrown transport exceptions.
Use `runTest` for recovery intervals and existing independent crypto vectors for encrypted transports.
This method fits the existing `TerminalClientTest` fixture and imports; adapt its request expectations to the regression:

```kotlin
@Test
fun `unanswered payment remains unknown without a conclusive status`() = runTest {
    val outcome = client { request ->
        if (request.saleToPOIRequest.paymentRequest != null) Delivery.MaybeSent("timeout") else empty
    }.pay(params)
    assertThat(outcome).isInstanceOf(TransactionOutcome.Unknown::class.java)
    assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
}
```

Assert the outgoing identity/request facts and typed outcome; never infer financial failure from a missing reply.
At app level, use `FakeTerminal`, `FakeCloud`, `FakePaymentsApp`, `FakeManagement` and `FakeLinkApi` from
`TestSupport.kt`; `env.useLinks()` enables link setup. No network/DNS or real credentials.
Run `scripts/dev test adyen '*TerminalClientTest'`, then the affected app recovery test.

All starters are adaptation patterns, not extra tests to copy unchanged. Finish with
[the iteration workflow](../minimpos-iteration/SKILL.md); targeted success never replaces the full quality gate.
