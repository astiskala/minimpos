package io.github.astiskala.minimpos.core.shopper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShopperReferencesTest {
    @Test
    fun `hashed references are stable, normalised and salted`() {
        val a = ShopperReferences.fromEmail(" S.Hopper@Example.com ", EmailReferenceMode.HASHED)
        val b = ShopperReferences.fromEmail("s.hopper@example.com", EmailReferenceMode.HASHED)
        val salted = ShopperReferences.fromEmail("s.hopper@example.com", EmailReferenceMode.HASHED, salt = "store-1")
        assertThat(a).isEqualTo(b)
        assertThat(a).matches("em_[0-9a-f]{32}")
        assertThat(salted).isNotEqualTo(a)
        assertThat(a).doesNotContain("hopper")
    }

    @Test
    fun `raw references are the normalised email`() {
        assertThat(
            ShopperReferences.fromEmail(" S.Hopper@Example.com", EmailReferenceMode.RAW),
        ).isEqualTo("s.hopper@example.com")
    }

    @Test
    fun `validates emails and references`() {
        assertThat(ShopperReferences.isValidEmail("a@b.co")).isTrue()
        assertThat(ShopperReferences.isValidEmail("a@b")).isFalse()
        assertThat(ShopperReferences.isValidEmail("a b@c.com")).isFalse()
        assertThat(ShopperReferences.isValidReference(" ab ")).isFalse()
        assertThat(ShopperReferences.isValidReference("abc")).isTrue()
    }
}
