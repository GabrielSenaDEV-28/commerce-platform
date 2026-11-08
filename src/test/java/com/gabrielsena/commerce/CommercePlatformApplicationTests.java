package com.gabrielsena.commerce;

import com.gabrielsena.commerce.merchant.MerchantRepository;
import com.gabrielsena.commerce.merchant.application.OnboardMerchant;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantCommand;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
import com.gabrielsena.commerce.merchant.domain.Merchant;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommercePlatformApplicationTests {

	private static final String TEST_DATABASE_URL = validateTestDatabaseUrl(System.getenv("TEST_DATABASE_URL"));
	private static final PostgreSQLContainer<?> POSTGRES = startPostgresContainerIfNeeded();

	@Autowired
	private OnboardMerchant onboardMerchant;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private StoreRepository storeRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	private static PostgreSQLContainer<?> startPostgresContainerIfNeeded() {
		if (TEST_DATABASE_URL != null) {
			return null;
		}

		PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
		postgres.start();
		return postgres;
	}

	private static String validateTestDatabaseUrl(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}

		URI databaseUri = URI.create(url.substring("jdbc:".length()));
		if (databaseUri.getPath() == null || !databaseUri.getPath().endsWith("_test")) {
			throw new IllegalStateException("TEST_DATABASE_URL must target a database whose name ends with _test");
		}
		return url;
	}

	@DynamicPropertySource
	static void configureDatabase(DynamicPropertyRegistry registry) {
		if (POSTGRES != null) {
			registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
			registry.add("spring.datasource.username", POSTGRES::getUsername);
			registry.add("spring.datasource.password", POSTGRES::getPassword);
			return;
		}

		registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
		registry.add("spring.datasource.username", () -> requiredEnvironmentVariable("TEST_DATABASE_USERNAME"));
		registry.add("spring.datasource.password", () -> requiredEnvironmentVariable("TEST_DATABASE_PASSWORD"));
	}

	private static String requiredEnvironmentVariable(String name) {
		String value = System.getenv(name);
		if (value == null) {
			throw new IllegalStateException(name + " must be set when using TEST_DATABASE_URL");
		}
		return value;
	}

	@AfterAll
	static void stopPostgresContainer() {
		if (POSTGRES != null) {
			POSTGRES.stop();
		}
	}

	@Test
	void contextLoads() {
	}

	@Test
	void shouldPersistMerchantAndStoreTogether() {
		String subdomain = uniqueSubdomain();

		OnboardMerchantResult result = onboardMerchant.execute(
				new OnboardMerchantCommand("Urban Store LTDA", "Urban Store", subdomain)
		);

		Merchant merchant = merchantRepository.findById(result.merchantId()).orElseThrow();
		Store store = storeRepository.findById(result.storeId()).orElseThrow();

		assertEquals("Urban Store LTDA", merchant.getName());
		assertEquals(merchant.getId(), store.getMerchantId());
		assertEquals("Urban Store", store.getName());
		assertEquals(subdomain, store.getSubdomain().getValue());
	}

	@Test
	void shouldEnforceUniqueSubdomainForConcurrentStoreWrites() throws Exception {
		Subdomain subdomain = Subdomain.of(uniqueSubdomain());
		Merchant firstMerchant = merchantRepository.saveAndFlush(Merchant.create("First " + UUID.randomUUID()));
		Merchant secondMerchant = merchantRepository.saveAndFlush(Merchant.create("Second " + UUID.randomUUID()));
		long existingStores = storeRepository.count();
		CountDownLatch startWrites = new CountDownLatch(1);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Store> firstWrite = executor.submit(() -> saveAfterStart(
					startWrites,
					Store.create(firstMerchant.getId(), "First Store", subdomain)
			));
			Future<Store> secondWrite = executor.submit(() -> saveAfterStart(
					startWrites,
					Store.create(secondMerchant.getId(), "Second Store", subdomain)
			));
			startWrites.countDown();

			int successfulWrites = 0;
			int rejectedWrites = 0;
			for (Future<Store> write : List.of(firstWrite, secondWrite)) {
				try {
					assertNotNull(write.get(15, TimeUnit.SECONDS));
					successfulWrites++;
				} catch (ExecutionException exception) {
					assertInstanceOf(DataIntegrityViolationException.class, exception.getCause());
					rejectedWrites++;
				}
			}

			assertEquals(1, successfulWrites);
			assertEquals(1, rejectedWrites);
			assertEquals(existingStores + 1, storeRepository.count());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void shouldRollbackMerchantWhenStorePersistenceFails() {
		long existingMerchants = merchantRepository.count();
		long existingStores = storeRepository.count();
		createFailingStoreTrigger();

		try {
			assertThrows(
					DataAccessException.class,
					() -> onboardMerchant.execute(
							new OnboardMerchantCommand(
									"Rollback Merchant",
									"force-test-insert-failure",
									uniqueSubdomain()
							)
					)
			);

			assertEquals(existingMerchants, merchantRepository.count());
			assertEquals(existingStores, storeRepository.count());
		} finally {
			dropFailingStoreTrigger();
		}
	}

	@Test
	void databaseShouldEnforceUniqueSubdomains() {
		String subdomain = uniqueSubdomain();
		Merchant firstMerchant = merchantRepository.saveAndFlush(Merchant.create("First Merchant"));
		Merchant secondMerchant = merchantRepository.saveAndFlush(Merchant.create("Second Merchant"));

		storeRepository.saveAndFlush(
				Store.create(firstMerchant.getId(), "First Store", Subdomain.of(subdomain))
		);

		assertThrows(
				DataIntegrityViolationException.class,
				() -> storeRepository.saveAndFlush(
						Store.create(secondMerchant.getId(), "Second Store", Subdomain.of(subdomain))
				)
		);
	}

	@Test
	void shouldCreateMerchantAndStoreThroughPublicApi() throws Exception {
		String subdomain = uniqueSubdomain();

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": "Urban Store LTDA",
								  "storeName": "Urban Store",
								  "subdomain": "%s"
								}
								""".formatted(subdomain)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.merchantId").isNotEmpty())
				.andExpect(jsonPath("$.storeId").isNotEmpty())
				.andExpect(jsonPath("$.merchantName").value("Urban Store LTDA"))
				.andExpect(jsonPath("$.storeName").value("Urban Store"))
				.andExpect(jsonPath("$.subdomain").value(subdomain));

		assertEquals(1, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM stores WHERE subdomain = ?",
				Integer.class,
				subdomain
		));
		assertEquals(1, jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM merchants m
				JOIN stores s ON s.merchant_id = m.merchant_id
				WHERE s.subdomain = ?
				""",
				Integer.class,
				subdomain
		));
	}

	@Test
	void shouldRejectInvalidMerchantApiRequestWithoutPersisting() throws Exception {
		long existingMerchants = merchantRepository.count();
		long existingStores = storeRepository.count();

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": " ",
								  "storeName": "Urban Store",
								  "subdomain": "urban-store"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors[0]").value("merchantName: must not be blank"));

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": "Urban Store LTDA",
								  "storeName": "Urban Store",
								  "subdomain": "invalid_subdomain"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		assertEquals(existingMerchants, merchantRepository.count());
		assertEquals(existingStores, storeRepository.count());
	}

	@Test
	void shouldReturnConflictForDuplicateMerchantApiSubdomain() throws Exception {
		String subdomain = uniqueSubdomain();
		String request = """
				{
				  "merchantName": "Urban Store LTDA",
				  "storeName": "Urban Store",
				  "subdomain": "%s"
				}
				""".formatted(subdomain);

		mockMvc.perform(post("/api/merchants").contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isCreated());

		long merchantCountAfterFirstRequest = merchantRepository.count();
		long storeCountAfterFirstRequest = storeRepository.count();

		mockMvc.perform(post("/api/merchants").contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("The requested store subdomain is already in use."));

		assertEquals(merchantCountAfterFirstRequest, merchantRepository.count());
		assertEquals(storeCountAfterFirstRequest, storeRepository.count());
	}

	private Store saveAfterStart(CountDownLatch startWrites, Store store) throws InterruptedException {
		if (!startWrites.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent store writes did not start");
		}
		return storeRepository.saveAndFlush(store);
	}

	private void createFailingStoreTrigger() {
		dropFailingStoreTrigger();
		jdbcTemplate.execute("""
				CREATE OR REPLACE FUNCTION commerce_test_fail_store_insert()
				RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.name = 'force-test-insert-failure' THEN
						RAISE EXCEPTION 'Simulated store insert failure';
					END IF;
					RETURN NEW;
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER commerce_test_fail_store_insert
				BEFORE INSERT ON stores
				FOR EACH ROW EXECUTE FUNCTION commerce_test_fail_store_insert()
				""");
	}

	private void dropFailingStoreTrigger() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS commerce_test_fail_store_insert ON stores");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS commerce_test_fail_store_insert()");
	}

	private String uniqueSubdomain() {
		return "store-" + UUID.randomUUID().toString().substring(0, 8);
	}
}
