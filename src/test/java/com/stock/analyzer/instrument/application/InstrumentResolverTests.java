package com.stock.analyzer.instrument.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.instrument.domain.InstrumentProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstrumentResolverTests {

	@Test
	void returnsCachedInstrumentWithoutCallingProvider() {
		Instrument cached = instrument("AAPL");
		RecordingRepository repository = new RecordingRepository(Optional.of(cached));
		RecordingProvider provider = new RecordingProvider(Optional.empty());

		Instrument result = new InstrumentResolver(repository, provider).resolve(" aapl ", false);

		assertThat(result).isSameAs(cached);
		assertThat(repository.lastLookupSymbol).isEqualTo("AAPL");
		assertThat(provider.lastLookupSymbol).isNull();
	}

	@Test
	void refreshesFromProviderAndPersistsCanonicalProfile() {
		InstrumentProfile profile = profile("AAPL");
		RecordingRepository repository = new RecordingRepository(Optional.empty());
		RecordingProvider provider = new RecordingProvider(Optional.of(profile));

		Instrument result = new InstrumentResolver(repository, provider).resolve("aapl", true);

		assertThat(provider.lastLookupSymbol).isEqualTo("AAPL");
		assertThat(repository.savedProfile).isSameAs(profile);
		assertThat(result.symbol()).isEqualTo("AAPL");
	}

	@Test
	void rejectsInvalidSymbolBeforeUsingProvider() {
		RecordingRepository repository = new RecordingRepository(Optional.empty());
		RecordingProvider provider = new RecordingProvider(Optional.empty());

		assertThatThrownBy(() -> new InstrumentResolver(repository, provider).resolve("AAPL!", false))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(exception.code()).isEqualTo("INVALID_SYMBOL");
				});
		assertThat(provider.lastLookupSymbol).isNull();
	}

	@Test
	void reportsUnknownInstrument() {
		RecordingRepository repository = new RecordingRepository(Optional.empty());
		RecordingProvider provider = new RecordingProvider(Optional.empty());

		assertThatThrownBy(() -> new InstrumentResolver(repository, provider).resolve("UNKNOWN", false))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(exception.code()).isEqualTo("INSTRUMENT_NOT_FOUND");
				});
	}

	private static Instrument instrument(String symbol) {
		return new Instrument(UUID.randomUUID(), symbol, "Apple Inc", "NASDAQ", "USD", true,
				"Technology", "Consumer Electronics", "199059", "stocks", LocalDate.of(2026, 10, 2));
	}

	private static InstrumentProfile profile(String symbol) {
		return new InstrumentProfile("SHARADAR", "stocks", "199059", symbol, "Apple Inc", "NASDAQ",
				"USD", true, "Domestic Common Stock", "Technology", "Consumer Electronics",
				null, null, null, null, null, null, null, null,
				null, null, null, null, null,
				LocalDate.of(2026, 10, 2), Map.of());
	}

	private static final class RecordingRepository implements InstrumentRepository {

		private final Optional<Instrument> cached;
		private String lastLookupSymbol;
		private InstrumentProfile savedProfile;

		private RecordingRepository(Optional<Instrument> cached) {
			this.cached = cached;
		}

		@Override
		public Optional<Instrument> findActiveBySymbol(String symbol) {
			lastLookupSymbol = symbol;
			return cached;
		}

		@Override
		public Instrument upsert(InstrumentProfile profile) {
			savedProfile = profile;
			return instrument(profile.symbol());
		}
	}

	private static final class RecordingProvider implements InstrumentReferenceProvider {

		private final Optional<InstrumentProfile> result;
		private String lastLookupSymbol;

		private RecordingProvider(Optional<InstrumentProfile> result) {
			this.result = result;
		}

		@Override
		public Optional<InstrumentProfile> findBySymbol(String symbol) {
			lastLookupSymbol = symbol;
			return result;
		}
	}
}
