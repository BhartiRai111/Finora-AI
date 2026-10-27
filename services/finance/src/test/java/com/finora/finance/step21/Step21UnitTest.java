package com.finora.finance.step21;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.error.ApiException;
import com.finora.finance.accounting.AccountingConfiguration;
import com.finora.finance.accounting.Redaction;
import com.finora.finance.accounting.TokenVault;
import com.finora.finance.accounting.WebhookVerifier;
import com.finora.finance.fx.RateService;
import com.finora.finance.gst.GstCodes;
import com.finora.finance.money.Currencies;
import com.finora.finance.regional.FiscalPeriods;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Currency metadata, rate validation, conversion arithmetic, fiscal periods, GSTIN structure, token vault, webhook signatures and redaction, without a database. */
class Step21UnitTest {

    // ------------------------------------------------------------------ currency metadata and arithmetic

    @Test
    void minorUnitsFollowTheIsoMetadataAndUnknownCurrenciesAreRefused() {
        assertThat(Currencies.exponent("INR")).contains(2);
        assertThat(Currencies.exponent("JPY")).contains(0);
        assertThat(Currencies.exponent("KRW")).contains(0);
        assertThat(Currencies.exponent("KWD")).contains(3);
        assertThat(Currencies.exponent("BHD")).contains(3);
        assertThat(Currencies.supported("XXX")).isFalse();
        assertThat(Currencies.supported("inr")).as("codes are upper case").isFalse();
        assertThat(Currencies.supported(null)).isFalse();
    }

    @Test
    void aConversionRoundsOnceHalfEvenToTheTargetMinorUnit() {
        assertThat(RateService.convert(new BigDecimal("100.10"), new BigDecimal("83.2150"), "INR")).isEqualByComparingTo("8329.82");
        assertThat(RateService.convert(new BigDecimal("0.125"), BigDecimal.ONE, "USD").toPlainString()).as("0.125 -> 0.12 (half-even)").isEqualTo("0.12");
        assertThat(RateService.convert(new BigDecimal("0.135"), BigDecimal.ONE, "USD").toPlainString()).as("0.135 -> 0.14 (half-even)").isEqualTo("0.14");
        assertThat(RateService.convert(new BigDecimal("1.00"), new BigDecimal("149.5"), "JPY").toPlainString()).as("a zero-decimal target").isEqualTo("150");
        assertThat(RateService.convert(new BigDecimal("1.00"), new BigDecimal("0.30835"), "KWD").toPlainString()).as("a three-decimal target").isEqualTo("0.308");
        assertThat(RateService.convert(new BigDecimal("5000"), new BigDecimal("0.0067"), "USD").toPlainString()).isEqualTo("33.50");
    }

    @Test
    void convertingTheOriginalAgainGivesTheSameFigureWhileChainingWouldDrift() {
        BigDecimal original = new BigDecimal("999.99");
        BigDecimal first = RateService.convert(original, new BigDecimal("0.01203"), "USD");
        for (int i = 0; i < 5; i++) {
            assertThat(RateService.convert(original, new BigDecimal("0.01203"), "USD")).isEqualByComparingTo(first);
        }
        BigDecimal viaJpy = RateService.convert(RateService.convert(original, new BigDecimal("1.7"), "JPY"), new BigDecimal("0.3"), "USD");
        assertThat(viaJpy.toPlainString()).as("a two-step chain rounds twice (510.00)...").isEqualTo("510.00");
        assertThat(RateService.convert(original, new BigDecimal("1.7").multiply(new BigDecimal("0.3")), "USD").toPlainString()).as("...and differs from converting the original once").isEqualTo("509.99");
    }

    @Test
    void invalidRatesAreRejected() {
        for (String bad : new String[] {null, "", " ", "abc", "0", "0.0", "-1", "-0.0001", "1.00000000001", "1000000000.1", "NaN", "1e3x"}) {
            assertThatThrownBy(() -> RateService.parseRate(bad)).as(String.valueOf(bad)).isInstanceOf(ApiException.class);
        }
        assertThat(RateService.parseRate("83.2150")).isEqualByComparingTo("83.215");
        assertThat(RateService.parseRate("0.0000000001")).isPositive();
        assertThat(RateService.parseRate("1000000000")).isEqualByComparingTo("1000000000");
        assertThatThrownBy(() -> RateService.convert(BigDecimal.ONE, BigDecimal.ZERO, "INR")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ fiscal periods

    @Test
    void fiscalYearsFollowTheConfiguredStartMonth() {
        var april = FiscalPeriods.presets(LocalDate.parse("2026-03-15"), 4);
        assertThat(april.get("this_fiscal_year").from()).isEqualTo(LocalDate.parse("2025-04-01"));
        assertThat(april.get("this_fiscal_year").end()).isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(april.get("this_fiscal_year").to()).as("capped at today while running").isEqualTo(LocalDate.parse("2026-03-15"));
        assertThat(april.get("last_fiscal_year").from()).isEqualTo(LocalDate.parse("2024-04-01"));
        assertThat(april.get("last_fiscal_year").to()).isEqualTo(LocalDate.parse("2025-03-31"));
        assertThat(april.get("this_fiscal_quarter").from()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(april.get("last_fiscal_quarter").from()).isEqualTo(LocalDate.parse("2025-10-01"));
        assertThat(april.get("last_fiscal_quarter").to()).isEqualTo(LocalDate.parse("2025-12-31"));
        var april1 = FiscalPeriods.presets(LocalDate.parse("2026-04-01"), 4);
        assertThat(april1.get("this_fiscal_year").from()).as("the first day of a fiscal year belongs to it").isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(april1.get("last_fiscal_year").end()).isEqualTo(LocalDate.parse("2026-03-31"));
        var calendar = FiscalPeriods.presets(LocalDate.parse("2026-03-15"), 1);
        assertThat(calendar.get("this_fiscal_year").from()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(calendar.get("last_fiscal_quarter").from()).isEqualTo(LocalDate.parse("2025-10-01"));
        var november = FiscalPeriods.presets(LocalDate.parse("2026-01-31"), 11);
        assertThat(november.get("this_fiscal_year").from()).isEqualTo(LocalDate.parse("2025-11-01"));
        assertThat(november.get("this_fiscal_year").end()).isEqualTo(LocalDate.parse("2026-10-31"));
    }

    // ------------------------------------------------------------------ GST structure

    @Test
    void theGstinCheckCharacterMatchesPublishedSampleNumbers() {
        for (String g : new String[] {"27AAPFU0939F1ZV", "29AAGCB7383J1Z4"}) {
            assertThat(GstCodes.problem(g)).as(g).isNull();
            assertThat(GstCodes.checkCharacter(g.substring(0, 14))).isEqualTo(g.charAt(14));
        }
        assertThat(GstCodes.problem("27AAPFU0939F1ZX")).contains("check character");
        assertThat(GstCodes.problem("27AAPFU0939F1Y5")).as("the 14th character must be Z").contains("GSTIN format");
        assertThat(GstCodes.problem("27aapfu0939f1zv")).contains("GSTIN format");
        assertThat(GstCodes.problem("27AAPFU0939F1Z")).contains("GSTIN format");
        assertThat(GstCodes.problem(null)).isNotNull();
        assertThat(GstCodes.problem("40AAPFU0939F1ZV")).as("no state code 40").isNotNull();
        assertThat(GstCodes.stateOf("27AAPFU0939F1ZV")).contains("27");
        assertThat(GstCodes.mask("27AAPFU0939F1ZV")).isEqualTo("27**********1ZV").doesNotContain("AAPFU");
    }

    @Test
    void stateCodesAndTheUnsupportedIntraStateCases() {
        assertThat(GstCodes.states()).containsEntry("27", "Maharashtra").containsEntry("29", "Karnataka").containsEntry("07", "Delhi").containsEntry("37", "Andhra Pradesh").containsEntry("38", "Ladakh");
        assertThat(GstCodes.usablePlaceOfSupply("27")).isTrue();
        assertThat(GstCodes.usablePlaceOfSupply("97")).isFalse();
        assertThat(GstCodes.usablePlaceOfSupply("99")).isFalse();
        assertThat(GstCodes.usablePlaceOfSupply("00")).isFalse();
        assertThat(GstCodes.usablePlaceOfSupply(null)).isFalse();
        for (String ut : new String[] {"04", "26", "31", "35", "38"}) {
            assertThat(GstCodes.intraStateUnsupported(ut)).as(ut).isTrue();
        }
        assertThat(GstCodes.intraStateUnsupported("07")).as("Delhi has a legislature").isFalse();
    }

    // ------------------------------------------------------------------ secrets and signatures

    @Test
    void theTokenVaultEncryptsBindsToTheWorkspaceAndNeverPrintsASecret(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Path file = dir.resolve("key");
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        TokenVault vault = new AccountingConfiguration().tokenVault(file.toString());
        assertThat(vault.available()).isTrue();
        UUID tenant = UUID.randomUUID();
        String sealed = vault.seal(tenant, "prov", "refresh-token-SECRET-123");
        assertThat(sealed).startsWith("v1:").doesNotContain("SECRET");
        assertThat(vault.seal(tenant, "prov", "refresh-token-SECRET-123")).as("a fresh IV every time").isNotEqualTo(sealed);
        assertThat(vault.open(tenant, "prov", sealed)).isEqualTo("refresh-token-SECRET-123");
        assertThatThrownBy(() -> vault.open(UUID.randomUUID(), "prov", sealed)).as("another workspace cannot open it").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vault.open(tenant, "other", sealed)).isInstanceOf(IllegalArgumentException.class);
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> vault.open(tenant, "prov", tampered)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> vault.open(tenant, "prov", "plain")).isInstanceOf(IllegalArgumentException.class);
        assertThat(vault.toString()).doesNotContain("SECRET").doesNotContain(Base64.getEncoder().encodeToString(key));
        TokenVault none = new AccountingConfiguration().tokenVault("");
        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> none.seal(tenant, "p", "x")).isInstanceOf(IllegalStateException.class);
        Files.writeString(dir.resolve("short"), Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(new AccountingConfiguration().tokenVault(dir.resolve("short").toString()).available()).as("a key that is not 256 bits is refused").isFalse();
        assertThat(new AccountingConfiguration().tokenVault(dir.resolve("missing").toString()).available()).isFalse();
    }

    @Test
    void onlyNoneIsAnAcceptedAccountingProvider() {
        AccountingConfiguration c = new AccountingConfiguration();
        assertThat(c.accountingProvider("none").configured()).isFalse();
        assertThat(c.accountingProvider("").key()).isEqualTo("none");
        for (String unknown : new String[] {"quickbooks", "tally", "zoho", "xero", "test"}) {
            assertThatThrownBy(() -> c.accountingProvider(unknown)).isInstanceOf(IllegalStateException.class).hasMessageContaining("No accounting provider is approved");
        }
    }

    @Test
    void webhookSignaturesAreVerifiedInConstantTimeWithinAReplayWindow() {
        byte[] secret = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-03-15T10:00:00Z");
        long ts = now.getEpochSecond();
        String sig = WebhookVerifier.sign(secret, ts, body);
        Duration tol = Duration.ofMinutes(5);
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts), body, sig, now, tol)).isTrue();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts), body, sig.toUpperCase(), now, tol)).as("hex case does not matter").isTrue();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts), "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8), sig, now, tol)).as("changed body").isFalse();
        assertThat(WebhookVerifier.verify("other".getBytes(StandardCharsets.UTF_8), String.valueOf(ts), body, sig, now, tol)).as("wrong secret").isFalse();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts - 301), body, WebhookVerifier.sign(secret, ts - 301, body), now, tol)).as("too old: replay").isFalse();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts + 301), body, WebhookVerifier.sign(secret, ts + 301, body), now, tol)).as("too far in the future").isFalse();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts - 299), body, WebhookVerifier.sign(secret, ts - 299, body), now, tol)).isTrue();
        assertThat(WebhookVerifier.verify(secret, "not-a-number", body, sig, now, tol)).isFalse();
        assertThat(WebhookVerifier.verify(secret, null, body, sig, now, tol)).isFalse();
        assertThat(WebhookVerifier.verify(secret, String.valueOf(ts), body, null, now, tol)).isFalse();
        assertThat(WebhookVerifier.verify(new byte[0], String.valueOf(ts), body, sig, now, tol)).isFalse();
        assertThat(WebhookVerifier.verify(null, String.valueOf(ts), body, sig, now, tol)).isFalse();
    }

    @Test
    void errorTextsLoseTokensAndSecretsBeforeTheyAreStoredOrShown() {
        String raw = "401 from provider: Authorization: Bearer abc.def.ghi-123456 access_token=zzzzzzzzzzzz client_secret=hunter2 key AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA end";
        String safe = Redaction.safe(raw);
        assertThat(safe).doesNotContain("abc.def").doesNotContain("zzzzzzzzzzzz").doesNotContain("hunter2").doesNotContain("AAAAAAAAAAAAAAAA").contains("[redacted]").contains("401 from provider");
        assertThat(Redaction.safe(null)).isNull();
        assertThat(Redaction.safe("x".repeat(500))).hasSizeLessThanOrEqualTo(300);
        assertThat(Redaction.safe("line1\nline2\u0000")).doesNotContain("\n").doesNotContain("\u0000");
    }
}
