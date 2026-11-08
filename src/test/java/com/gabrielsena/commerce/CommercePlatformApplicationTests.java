package com.gabrielsena.commerce;

import com.gabrielsena.commerce.merchant.MerchantRepository;
import com.gabrielsena.commerce.merchant.application.OnboardMerchant;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantCommand;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
import com.gabrielsena.commerce.merchant.domain.Merchant;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.RefreshTokenRepository;
import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.auth.application.AuthenticateMerchant;
import com.gabrielsena.commerce.auth.application.RefreshAuthentication;
import com.gabrielsena.commerce.auth.application.RegisterMerchant;
import com.gabrielsena.commerce.auth.application.RegisterMerchantCommand;
import com.gabrielsena.commerce.auth.application.TokenPair;
import com.gabrielsena.commerce.auth.application.TokenService;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.catalog.ItemTypeNameInUseException;
import com.gabrielsena.commerce.catalog.CatalogConflictException;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.application.ItemTypeService;
import com.gabrielsena.commerce.catalog.application.ProductService;
import com.gabrielsena.commerce.catalog.api.ProductRequest;
import com.gabrielsena.commerce.catalog.api.VariantRequest;
import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.ItemTypeValues;
import com.gabrielsena.commerce.catalog.domain.Product;
import com.gabrielsena.commerce.catalog.domain.SizeFormat;
import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.checkout.application.CheckoutReservationService;
import com.gabrielsena.commerce.order.application.OrderService;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.inventory.application.InventoryService;
import com.gabrielsena.commerce.inventory.application.InventoryService.MovementType;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommercePlatformApplicationTests {

	private static final String TEST_DATABASE_URL = validateTestDatabaseUrl(System.getenv("TEST_DATABASE_URL"));
	private static final PostgreSQLContainer<?> POSTGRES = startPostgresContainerIfNeeded();
	private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
			"commerce-platform-test-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
	);

	@Autowired
	private OnboardMerchant onboardMerchant;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private StoreRepository storeRepository;

	@Autowired
	private MerchantAccountRepository merchantAccountRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private RegisterMerchant registerMerchant;

	@Autowired
	private AuthenticateMerchant authenticateMerchant;

	@Autowired
	private RefreshAuthentication refreshAuthentication;

	@Autowired
	private ItemTypeService itemTypeService;

	@Autowired
	private ProductService productService;

	@Autowired
	private InventoryService inventoryService;

	@Autowired
	private ShoppingCartService shoppingCartService;

	@Autowired
	private CheckoutReservationService checkoutReservationService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private CustomerAuthService customerAuthService;

	@Autowired
	private CustomerProfileService customerProfileService;

	@Autowired
	private JwtEncoder jwtEncoder;

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
		registry.add("security.jwt.secret", () -> TEST_JWT_SECRET);
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
	void itemTypesShouldSupportCrudAuditAndStoreIsolation() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String initial = """
				{"name":" Camiseta ","hasColor":true,"hasSize":true,"sizeFormat":"LETTER"}
				""";
		String changed = """
				{"name":"Tênis","hasColor":true,"hasSize":true,"sizeFormat":"NUMERIC"}
				""";
		mockMvc.perform(post("/api/item-types").contentType(APPLICATION_JSON).content(initial))
				.andExpect(status().isUnauthorized());
		String body = mockMvc.perform(post("/api/item-types")
						.header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(initial))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Camiseta"))
				.andExpect(jsonPath("$.createdBy").value(owner.id().toString()))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");

		mockMvc.perform(post("/api/item-types").header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON)
						.content("""
								{"name":"camiseta","hasColor":false,"hasSize":false}
								"""))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/item-types").header("Authorization", "Bearer " + other.token())
						.contentType(APPLICATION_JSON).content(initial))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/item-types").header("Authorization", "Bearer " + owner.token())
						.param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(id));
		mockMvc.perform(get("/api/item-types").header("Authorization", "Bearer " + owner.token())
						.param("size", "101"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/item-types/{id}", id).header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/item-types/{id}", id).header("Authorization", "Bearer " + other.token())
						.contentType(APPLICATION_JSON).content(changed))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/item-types/{id}", id).header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/item-types/{id}/history", id).header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());

		mockMvc.perform(put("/api/item-types/{id}", id).header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(changed))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tênis"))
				.andExpect(jsonPath("$.sizeFormat").value("NUMERIC"))
				.andExpect(jsonPath("$.updatedBy").value(owner.id().toString()));
		mockMvc.perform(get("/api/item-types/{id}", id).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tênis"));
		mockMvc.perform(delete("/api/item-types/{id}", id).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/item-types/{id}", id).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/item-types/{id}/history", id).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].action").value("CREATED"))
				.andExpect(jsonPath("$.content[1].before.name").value("Camiseta"))
				.andExpect(jsonPath("$.content[1].after.name").value("Tênis"))
				.andExpect(jsonPath("$.content[2].action").value("DELETED"))
				.andExpect(jsonPath("$.content[2].actorId").value(owner.id().toString()));
	}

	@Test
	void itemTypesShouldRejectInvalidConfigurations() throws Exception {
		CatalogActor actor = catalogActor();
		for (String body : List.of(
				"{\"name\":\" \",\"hasColor\":true,\"hasSize\":false}",
				"{\"name\":\"Shoes\",\"hasColor\":true,\"hasSize\":true}",
				"{\"name\":\"Shoes\",\"hasColor\":true,\"hasSize\":false,\"sizeFormat\":\"LETTER\"}",
				"{\"name\":\"Shoes\",\"hasSize\":false}",
				"{\"name\":\"Shoes\",\"hasColor\":true,\"hasSize\":true,\"sizeFormat\":\"OTHER\"}"
		)) {
			mockMvc.perform(post("/api/item-types").header("Authorization", "Bearer " + actor.token())
							.contentType(APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void itemTypesShouldPaginateAndRejectConflictingRenames() throws Exception {
		CatalogActor actor = catalogActor();
		String token = "Bearer " + actor.token();
		String shirt = "{\"name\":\"Camiseta\",\"hasColor\":true,\"hasSize\":false}";
		String shoes = "{\"name\":\"Tênis\",\"hasColor\":true,\"hasSize\":true,\"sizeFormat\":\"NUMERIC\"}";
		String id = JsonPath.read(mockMvc.perform(post("/api/item-types").header("Authorization", token)
						.contentType(APPLICATION_JSON).content(shoes))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/item-types").header("Authorization", token)
						.contentType(APPLICATION_JSON).content(shirt)).andExpect(status().isCreated());
		mockMvc.perform(put("/api/item-types/{id}", id).header("Authorization", token)
						.contentType(APPLICATION_JSON).content(shirt)).andExpect(status().isConflict());
		mockMvc.perform(get("/api/item-types").header("Authorization", token).param("size", "1").param("page", "0"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].name").value("Camiseta"));
		mockMvc.perform(get("/api/item-types").header("Authorization", token).param("size", "1").param("page", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Tênis"));
		mockMvc.perform(get("/api/item-types/{id}", id).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tênis"));
		mockMvc.perform(get("/api/item-types/{id}/history", id).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void itemTypesShouldResolveStoreFromAccountRatherThanTokenMerchantClaim() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String id = JsonPath.read(mockMvc.perform(post("/api/item-types")
						.header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON)
						.content("{\"name\":\"Camiseta\",\"hasColor\":true,\"hasSize\":false}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		UUID ownerMerchantId = merchantAccountRepository.findById(owner.id()).orElseThrow().getMerchantId();
		String mismatchedToken = tokenWithClaims(other.id(), ownerMerchantId);
		mockMvc.perform(get("/api/item-types/{id}", id).header("Authorization", "Bearer " + mismatchedToken))
				.andExpect(status().isNotFound());
		String unknownAccount = tokenWithClaims(UUID.randomUUID(), ownerMerchantId);
		mockMvc.perform(get("/api/item-types").header("Authorization", "Bearer " + unknownAccount))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void itemTypesShouldEnforceUniqueNameUnderConcurrency() throws Exception {
		CatalogActor actor = catalogActor();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ItemType> first = executor.submit(() -> createItemAfterStart(start, actor.id(), " Camiseta "));
			Future<ItemType> second = executor.submit(() -> createItemAfterStart(start, actor.id(), "camiseta"));
			start.countDown();
			int created = 0;
			int rejected = 0;
			for (Future<ItemType> result : List.of(first, second)) {
				try {
					assertNotNull(result.get(15, TimeUnit.SECONDS));
					created++;
				} catch (ExecutionException exception) {
					assertInstanceOf(ItemTypeNameInUseException.class, exception.getCause());
					rejected++;
				}
			}
			assertEquals(1, created);
			assertEquals(1, rejected);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void itemTypeAndAuditShouldRollbackTogether() {
		CatalogActor actor = catalogActor();
		jdbcTemplate.execute("""
				CREATE OR REPLACE FUNCTION commerce_test_fail_item_history()
				RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.new_name = 'force-history-failure' THEN
						RAISE EXCEPTION 'Simulated history insert failure';
					END IF;
					RETURN NEW;
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER commerce_test_fail_item_history
				BEFORE INSERT ON catalog_item_type_history
				FOR EACH ROW EXECUTE FUNCTION commerce_test_fail_item_history()
				""");
		try {
			assertThrows(DataAccessException.class, () -> itemTypeService.create(actor.id(),
					new ItemTypeValues("force-history-failure", false, false, null)));
			assertEquals(0, jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM catalog_item_types WHERE name = ?", Integer.class, "force-history-failure"));
		} finally {
			jdbcTemplate.execute("DROP TRIGGER commerce_test_fail_item_history ON catalog_item_type_history");
			jdbcTemplate.execute("DROP FUNCTION commerce_test_fail_item_history()");
		}
	}

	@Test
	void productsShouldPreserveVariantStateAndHistoryAcrossDeactivation() throws Exception {
		CatalogActor actor = catalogActor();
		String token = "Bearer " + actor.token();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, true, SizeFormat.LETTER));
		String request = """
				{"itemTypeId":"%s","sku":" SKU-1 ","name":" Shirt ","description":"Cotton",
				"price":100.00,"variants":[{"color":"Black","size":"2XL"},{"color":"White","size":"M","price":120.00}]}
				""".formatted(type.getId());
		String productId = JsonPath.read(mockMvc.perform(post("/api/products").header("Authorization", token)
						.contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.sku").value("SKU-1"))
				.andExpect(jsonPath("$.createdBy").value(actor.id().toString()))
				.andReturn().getResponse().getContentAsString(), "$.id");
		String variantsBody = mockMvc.perform(get("/api/products/{id}/variants", productId)
						.header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(variantsBody, "$.content[*].id");
		List<String> sizes = JsonPath.read(variantsBody, "$.content[*].size");
		String blackId = ids.get(sizes.indexOf("2XL"));
		String whiteId = ids.get(sizes.indexOf("M"));
		mockMvc.perform(put("/api/products/{id}/variants/{variantId}", productId, blackId)
						.header("Authorization", token).contentType(APPLICATION_JSON).content("{\"price\":75.50}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.effectivePrice").value(75.50));
		mockMvc.perform(put("/api/products/{id}/variants/{variantId}", productId, blackId)
						.header("Authorization", token).contentType(APPLICATION_JSON).content("{\"price\":null}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.effectivePrice").value(100.00));
		mockMvc.perform(put("/api/products/{id}", productId).header("Authorization", token)
						.contentType(APPLICATION_JSON)
						.content("{\"sku\":\"SKU-1\",\"name\":\"Shirt updated\",\"description\":\"Cotton\",\"price\":110}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Shirt updated"))
				.andExpect(jsonPath("$.updatedBy").value(actor.id().toString()));
		mockMvc.perform(get("/api/products/{id}/variants", productId).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[*].effectivePrice", hasItem(110.0)));
		Integer historyCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM catalog_product_history WHERE product_id = ?",
				Integer.class, UUID.fromString(productId));
		mockMvc.perform(put("/api/products/{id}", productId).header("Authorization", token)
						.contentType(APPLICATION_JSON)
						.content("{\"sku\":\"SKU-1\",\"name\":\"Shirt updated\",\"description\":\"Cotton\",\"price\":110.00}"))
				.andExpect(status().isOk());
		assertEquals(historyCount, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM catalog_product_history WHERE product_id = ?",
				Integer.class, UUID.fromString(productId)));
		mockMvc.perform(delete("/api/products/{id}/variants/{variantId}", productId, whiteId)
				.header("Authorization", token)).andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/products/{id}", productId).header("Authorization", token))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/products").header("Authorization", token).param("active", "false"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(productId));
		mockMvc.perform(post("/api/products").header("Authorization", token)
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isConflict());
		mockMvc.perform(delete("/api/item-types/{id}", type.getId()).header("Authorization", token))
				.andExpect(status().isConflict());
		mockMvc.perform(put("/api/item-types/{id}", type.getId()).header("Authorization", token)
						.contentType(APPLICATION_JSON)
						.content("{\"name\":\"New name\",\"hasColor\":false,\"hasSize\":false}"))
				.andExpect(status().isConflict());
		mockMvc.perform(put("/api/item-types/{id}", type.getId()).header("Authorization", token)
						.contentType(APPLICATION_JSON)
						.content("{\"name\":\"New name\",\"hasColor\":true,\"hasSize\":true,\"sizeFormat\":\"LETTER\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/products/{id}/reactivate", productId).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
		mockMvc.perform(get("/api/products/{id}/variants", productId).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(blackId));
		mockMvc.perform(get("/api/products/{id}/variants", productId).header("Authorization", token)
						.param("active", "false"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(whiteId));
		mockMvc.perform(post("/api/products/{id}/variants/{variantId}/reactivate", productId, whiteId)
						.header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.effectivePrice").value(120.00));
		mockMvc.perform(get("/api/products/{id}/history", productId).header("Authorization", token)
						.param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].action", hasItem("REACTIVATED")))
				.andExpect(jsonPath("$.content[*].actorId", hasItem(actor.id().toString())));
	}

	@Test
	void shippingSettingsShouldValidateAndRemainPrivateToEachStore() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String token = "Bearer " + owner.token();
		String path = "/api/store/shipping-settings";
		String body = """
				{"postalCode":"01001000","street":" Rua A ","number":"10","complement":" ",
				 "district":"Centro","city":"Sao Paulo","state":"SP","pickupEnabled":true,
				 "pickupInstructions":" Bring ID "}
				""";
		mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isNotFound());
		for (String invalid : List.of(body.replace("01001000", "01001-000"),
				body.replace("\"SP\"", "\"XX\""), body.replace("\" Rua A \"", "\" \""),
				body.replace("\"pickupEnabled\":true", "\"pickupEnabled\":null"))) {
			mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON).content(invalid))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.street").value("Rua A"))
				.andExpect(jsonPath("$.complement").isEmpty())
				.andExpect(jsonPath("$.pickupEnabled").value(true))
				.andExpect(jsonPath("$.pickupInstructions").value("Bring ID"))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		mockMvc.perform(get(path).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
		mockMvc.perform(put(path).header("Authorization", "Bearer " + other.token()).contentType(APPLICATION_JSON)
						.content(body.replace("Rua A", "Rua B").replace("true", "false")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.pickupEnabled").value(false));
		mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.street").value("Rua A")).andExpect(jsonPath("$.pickupEnabled").value(true));
		mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON)
						.content(body.replace("true", "false")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.pickupEnabled").value(false));

		UUID merchant = merchantAccountRepository.findById(owner.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchant).orElseThrow().getId();
		customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		String customer = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		mockMvc.perform(get(path).header("Authorization", "Bearer " + customer)).andExpect(status().isForbidden());
	}

	@Test
	void variantShippingMeasurementsShouldValidatePreserveHistoryAndIsolateStores() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		InventoryFixture fixture = inventoryFixture(owner);
		String token = "Bearer " + owner.token();
		String path = "/api/products/" + fixture.productId() + "/variants/" + fixture.variantId() + "/shipping";
		String body = "{\"weightKg\":0.125,\"widthCm\":10.25,\"heightCm\":3,\"lengthCm\":20}";
		mockMvc.perform(get("/api/products/{id}/variants", fixture.productId()).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].shippingWeightKg").isEmpty());
		mockMvc.perform(put(path).contentType(APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		for (String invalid : List.of(body.replace("0.125", "0"), body.replace("0.125", "-1"),
				body.replace("0.125", "0.0001"), body.replace("10.25", "10.001"),
				body.replace("\"lengthCm\":20", "\"lengthCm\":null"),
				body.replace("0.125", "100000"))) {
			mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON).content(invalid))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(put(path).header("Authorization", "Bearer " + other.token())
						.contentType(APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
		mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shippingWeightKg").value(0.125))
				.andExpect(jsonPath("$.shippingWidthCm").value(10.25))
				.andExpect(jsonPath("$.shippingHeightCm").value(3))
				.andExpect(jsonPath("$.shippingLengthCm").value(20));
		Integer history = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM catalog_product_history WHERE variant_id = ? AND field_name LIKE 'shipping%'
				""", Integer.class, fixture.variantId());
		assertEquals(4, history);
		mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
		assertEquals(history, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM catalog_product_history WHERE variant_id = ? AND field_name LIKE 'shipping%'
				""", Integer.class, fixture.variantId()));
		mockMvc.perform(put(path).header("Authorization", token).contentType(APPLICATION_JSON)
						.content(body.replace("0.125", "0.500")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shippingWeightKg").value(0.5));
		mockMvc.perform(put("/api/products/{id}/variants/{variantId}", fixture.productId(), fixture.variantId())
						.header("Authorization", token).contentType(APPLICATION_JSON).content("{\"price\":5}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shippingWeightKg").value(0.5));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
				UPDATE catalog_product_variants SET shipping_length_cm = NULL WHERE variant_id = ?
				""", fixture.variantId()));
	}

	@Test
	void productsShouldValidateInputAndIsolateEveryStore() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String token = "Bearer " + owner.token();
		ItemType type = itemTypeService.create(owner.id(), new ItemTypeValues("Shoes", true, true, SizeFormat.NUMERIC));
		String base = "{\"itemTypeId\":\"%s\",\"sku\":\"one\",\"name\":\"Shoe\",\"description\":\"Shoe\",\"price\":10,\"variants\":%s}";
		for (String invalid : List.of("[]", "[{\"color\":\"Black\"}]", "[{\"color\":\"Black\",\"size\":\"40\",\"price\":-1}]",
				"[{\"color\":\"Black\",\"size\":\"40\",\"price\":1.001}]")) {
			mockMvc.perform(post("/api/products").header("Authorization", token).contentType(APPLICATION_JSON)
							.content(base.formatted(type.getId(), invalid))).andExpect(status().isBadRequest());
		}
		String duplicate = "[{\"color\":\" Black \",\"size\":\"38/40\"},{\"color\":\"black\",\"size\":\"38/40\"}]";
		mockMvc.perform(post("/api/products").header("Authorization", token).contentType(APPLICATION_JSON)
						.content(base.formatted(type.getId(), duplicate))).andExpect(status().isConflict());
		String productId = JsonPath.read(mockMvc.perform(post("/api/products").header("Authorization", token)
						.contentType(APPLICATION_JSON)
						.content(base.formatted(type.getId(), "[{\"color\":\"Black\",\"size\":\"38/40\"}]")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		String variantsBody = mockMvc.perform(get("/api/products/{id}/variants", productId).header("Authorization", token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String variantId = JsonPath.read(variantsBody, "$.content[0].id");
		mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/products").header("Authorization", token).param("size", "101"))
				.andExpect(status().isBadRequest());
		String otherToken = "Bearer " + other.token();
		mockMvc.perform(get("/api/products/{id}", productId).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/products/{id}/variants", productId).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/products/{id}/history", productId).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/products/{id}", productId).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(put("/api/products/{id}/variants/{variantId}", productId, variantId)
						.header("Authorization", otherToken).contentType(APPLICATION_JSON).content("{\"price\":2}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/products/{id}/variants", productId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content("{\"color\":\"BLACK\",\"size\":\"38/40\"}"))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/products").header("Authorization", otherToken).contentType(APPLICATION_JSON)
						.content(base.formatted(type.getId(), "[{\"color\":\"Black\",\"size\":\"40\"}]")))
				.andExpect(status().isNotFound());
		UUID merchantId = merchantAccountRepository.findById(owner.id()).orElseThrow().getMerchantId();
		mockMvc.perform(get("/api/products/{id}", productId)
						.header("Authorization", "Bearer " + tokenWithClaims(other.id(), merchantId)))
				.andExpect(status().isNotFound());
	}

	@Test
	void itemTypesShouldReserveInactiveNamesAndAllowRecovery() throws Exception {
		CatalogActor actor = catalogActor();
		String token = "Bearer " + actor.token();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Accessory", false, false, null));
		mockMvc.perform(delete("/api/item-types/{id}", type.getId()).header("Authorization", token))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/item-types").header("Authorization", token).param("active", "false"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].active").value(false));
		mockMvc.perform(post("/api/item-types").header("Authorization", token).contentType(APPLICATION_JSON)
						.content("{\"name\":\"accessory\",\"hasColor\":false,\"hasSize\":false}"))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/item-types/{id}/reactivate", type.getId()).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
		mockMvc.perform(get("/api/item-types/{id}/history", type.getId()).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[*].action", hasItem("REACTIVATED")));
	}

	@Test
	void productsWithoutAttributesHaveOneReservedCombinationPerProduct() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		String token = "Bearer " + actor.token();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Simple", false, false, null));
		ItemType otherType = itemTypeService.create(other.id(), new ItemTypeValues("Simple", false, false, null));
		String json = "{\"itemTypeId\":\"%s\",\"sku\":\"shared\",\"name\":\"Item\",\"description\":\"Item\",\"price\":0,\"variants\":[{}]}";
		String id = JsonPath.read(mockMvc.perform(post("/api/products").header("Authorization", token)
						.contentType(APPLICATION_JSON).content(json.formatted(type.getId())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + other.token())
						.contentType(APPLICATION_JSON).content(json.formatted(otherType.getId())))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/products/{id}/variants", id).header("Authorization", token)
						.contentType(APPLICATION_JSON).content("{\"color\":\"Red\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/products/{id}/variants", id).header("Authorization", token)
						.contentType(APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
		String body = mockMvc.perform(get("/api/products/{id}/variants", id).header("Authorization", token))
				.andReturn().getResponse().getContentAsString();
		String variantId = JsonPath.read(body, "$.content[0].id");
		mockMvc.perform(delete("/api/products/{id}/variants/{variantId}", id, variantId).header("Authorization", token))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/products/{id}/variants", id).header("Authorization", token)
						.contentType(APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
		mockMvc.perform(delete("/api/products/{id}", id).header("Authorization", token)).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/products/{id}/variants/{variantId}/reactivate", id, variantId)
				.header("Authorization", token)).andExpect(status().isConflict());
	}

	@Test
	void productSkuShouldRemainUniqueUnderConcurrentWrites() throws Exception {
		CatalogActor actor = catalogActor();
		ItemType firstType = itemTypeService.create(actor.id(), new ItemTypeValues("First", false, false, null));
		ItemType secondType = itemTypeService.create(actor.id(), new ItemTypeValues("Second", false, false, null));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Product> first = executor.submit(() -> createProductAfterStart(start, actor.id(), firstType.getId(), " SKU "));
			Future<Product> second = executor.submit(() -> createProductAfterStart(start, actor.id(), secondType.getId(), "sku"));
			start.countDown();
			int created = 0;
			int conflicts = 0;
			for (Future<Product> result : List.of(first, second)) {
				try {
					assertNotNull(result.get(15, TimeUnit.SECONDS));
					created++;
				} catch (ExecutionException exception) {
					assertInstanceOf(CatalogConflictException.class, exception.getCause());
					conflicts++;
				}
			}
			assertEquals(1, created);
			assertEquals(1, conflicts);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void productCreationAndTypeDeactivationMustNotBothSucceed() throws Exception {
		CatalogActor actor = catalogActor();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Simple", false, false, null));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Product> created = executor.submit(() -> createProductAfterStart(start, actor.id(), type.getId(), "race"));
			Future<?> deactivated = executor.submit(() -> {
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
				itemTypeService.delete(actor.id(), type.getId());
				return null;
			});
			start.countDown();
			boolean productSucceeded;
			try {
				created.get(15, TimeUnit.SECONDS);
				productSucceeded = true;
			} catch (ExecutionException exception) {
				assertInstanceOf(ItemTypeNotFoundException.class, exception.getCause());
				productSucceeded = false;
			}
			boolean deleteSucceeded;
			try {
				deactivated.get(15, TimeUnit.SECONDS);
				deleteSucceeded = true;
			} catch (ExecutionException exception) {
				assertInstanceOf(CatalogConflictException.class, exception.getCause());
				deleteSucceeded = false;
			}
			assertNotEquals(productSucceeded, deleteSucceeded);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void productAndHistoryShouldRollbackTogether() {
		CatalogActor actor = catalogActor();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Simple", false, false, null));
		jdbcTemplate.execute("""
				CREATE OR REPLACE FUNCTION commerce_test_fail_product_history()
				RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.new_value = 'force-product-history-failure' THEN
						RAISE EXCEPTION 'Simulated product history failure';
					END IF;
					RETURN NEW;
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER commerce_test_fail_product_history
				BEFORE INSERT ON catalog_product_history
				FOR EACH ROW EXECUTE FUNCTION commerce_test_fail_product_history()
				""");
		try {
			ProductRequest request = new ProductRequest(type.getId(), "force-product-history-failure", "Test",
					"Test", BigDecimal.TEN, List.of(new VariantRequest(null, null, null)));
			assertThrows(DataAccessException.class, () -> productService.create(actor.id(), request));
			assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_products WHERE sku = ?",
					Integer.class, "force-product-history-failure"));
		} finally {
			jdbcTemplate.execute("DROP TRIGGER commerce_test_fail_product_history ON catalog_product_history");
			jdbcTemplate.execute("DROP FUNCTION commerce_test_fail_product_history()");
		}
	}

	@Test
	void inventoryProductSummariesShouldIncludePhysicalAndEnabledStock() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, true, SizeFormat.LETTER));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SHIRT", "Shirt",
				"Cotton", BigDecimal.TEN, List.of(new VariantRequest("Black", "M", null),
				new VariantRequest("White", "L", null), new VariantRequest("Blue", "S", null))));
		UUID black = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = ?",
				UUID.class, product.getId(), "Black");
		UUID white = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = ?",
				UUID.class, product.getId(), "White");
		inventoryService.move(actor.id(), product.getId(), black, MovementType.ENTRY, 5, "Delivery", UUID.randomUUID());
		inventoryService.move(actor.id(), product.getId(), white, MovementType.ENTRY, 3, "Delivery", UUID.randomUUID());
		productService.setVariantActive(actor.id(), product.getId(), white, false);
		String token = "Bearer " + actor.token();
		String summary = "/api/inventory/products/" + product.getId();
		mockMvc.perform(get(summary)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(summary).header("Authorization", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.sku").value("SHIRT")).andExpect(jsonPath("$.name").value("Shirt"))
				.andExpect(jsonPath("$.description").value("Cotton"))
				.andExpect(jsonPath("$.physicalQuantity").value(8)).andExpect(jsonPath("$.enabledQuantity").value(5));
		mockMvc.perform(get("/api/inventory/products").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].physicalQuantity").value(8))
				.andExpect(jsonPath("$.content[0].enabledQuantity").value(5));
		String details = mockMvc.perform(get(summary + "/variants").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andReturn().getResponse().getContentAsString();
		assertEquals(List.of(5), JsonPath.read(details, "$.content[?(@.color == 'Black')].quantity"));
		assertEquals(List.of(0), JsonPath.read(details, "$.content[?(@.color == 'White')].enabledQuantity"));
		assertEquals(List.of(0), JsonPath.read(details, "$.content[?(@.color == 'Blue')].quantity"));
		mockMvc.perform(get(summary + "/variants").header("Authorization", token).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(3));
		mockMvc.perform(get(summary + "/variants").header("Authorization", token).param("size", "101"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(summary).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
		mockMvc.perform(get(summary + "/variants").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/inventory/products").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/inventory/products/{id}", UUID.randomUUID()).header("Authorization", token))
				.andExpect(status().isNotFound());
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		mockMvc.perform(get(summary).header("Authorization", "Bearer " + tokenWithClaims(other.id(), merchantId)))
				.andExpect(status().isNotFound());
		productService.setActive(actor.id(), product.getId(), false);
		mockMvc.perform(get(summary).header("Authorization", token))
				.andExpect(jsonPath("$.physicalQuantity").value(8)).andExpect(jsonPath("$.enabledQuantity").value(0));
		mockMvc.perform(get(summary + "/variants").header("Authorization", token))
				.andExpect(jsonPath("$.content[*].enabledQuantity", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
		mockMvc.perform(get("/api/inventory/products").header("Authorization", token).param("active", "false"))
				.andExpect(jsonPath("$.content[0].physicalQuantity").value(8))
				.andExpect(jsonPath("$.content[0].enabledQuantity").value(0));
		productService.setActive(actor.id(), product.getId(), true);
		mockMvc.perform(get(summary).header("Authorization", token)).andExpect(jsonPath("$.enabledQuantity").value(5));
	}

	@Test
	void publicCatalogShouldShowOnlyActiveProductsAndVariantsWithStockAndPrices() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(actor);
		InventoryFixture otherProduct = inventoryFixture(other);
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, true, SizeFormat.LETTER));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SHIRT", "Shirt",
				"Cotton", new BigDecimal("100.00"), List.of(
				new VariantRequest("Black", "M", null),
				new VariantRequest("White", "L", new BigDecimal("120.00")),
				new VariantRequest("Blue", "S", null))));
		UUID black = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = ?",
				UUID.class, product.getId(), "Black");
		UUID blue = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = ?",
				UUID.class, product.getId(), "Blue");
		inventoryService.move(actor.id(), product.getId(), black, MovementType.ENTRY, 5, "Delivery", UUID.randomUUID());
		productService.setVariantActive(actor.id(), product.getId(), blue, false);
		Product inactive = productService.create(actor.id(), new ProductRequest(type.getId(), "OTHER", "Inactive",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Black", "M", null))));
		productService.setActive(actor.id(), inactive.getId(), false);
		Product noActive = productService.create(actor.id(), new ProductRequest(type.getId(), "NONE", "No variants",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Black", "M", null))));
		UUID only = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ?",
				UUID.class, noActive.getId());
		productService.setVariantActive(actor.id(), noActive.getId(), only, false);
		String path = "/api/public/catalog/products";
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].name").value("Shirt"))
				.andExpect(jsonPath("$.content[0].price").value(100.00))
				.andExpect(jsonPath("$.content[0].createdBy").doesNotExist())
				.andExpect(jsonPath("$.content[0].sku").doesNotExist());
		mockMvc.perform(get(path + "/{id}", product.getId()).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Cotton"));
		String variants = mockMvc.perform(get(path + "/{id}/variants", product.getId())
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andReturn().getResponse().getContentAsString();
		assertEquals(List.of(5), JsonPath.read(variants, "$.content[?(@.color == 'Black')].quantity"));
		assertEquals(List.of(true), JsonPath.read(variants, "$.content[?(@.color == 'Black')].available"));
		assertEquals(List.of(0), JsonPath.read(variants, "$.content[?(@.color == 'White')].quantity"));
		assertEquals(List.of(false), JsonPath.read(variants, "$.content[?(@.color == 'White')].available"));
		assertEquals(List.of(120.0), JsonPath.read(variants, "$.content[?(@.color == 'White')].price"));
		mockMvc.perform(get(path + "/{id}", inactive.getId()).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path + "/{id}", noActive.getId()).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path + "/{id}/variants", inactive.getId()).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path + "/{id}", product.getId()).with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path).with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(otherProduct.productId().toString()));
		mockMvc.perform(get(path + "/{id}/variants", product.getId()).with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isNotFound());
	}

	@Test
	void publicCatalogShouldValidateHostPaginationAndSecurity() throws Exception {
		CatalogActor actor = catalogActor();
		String host = storefrontHost(actor);
		InventoryFixture fixture = inventoryFixture(actor);
		String path = "/api/public/catalog/products";
		for (String invalid : List.of("localhost", "unknown.localhost", "nested." + host,
				host + ".evil.example", "evil.example")) {
			mockMvc.perform(get(path).with(request -> { request.setServerName(invalid); return request; }))
					.andExpect(status().isNotFound());
		}
		mockMvc.perform(get(path).header("X-Forwarded-Host", host)
						.with(request -> { request.setServerName("evil.example"); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path).with(request -> { request.setServerName(host.toUpperCase()); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; }).param("size", "101"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; }).param("page", "-1"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(path + "/{id}/variants", fixture.productId())
						.with(request -> { request.setServerName(host); return request; }).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].quantity").value(0))
				.andExpect(jsonPath("$.content[0].available").value(false));
		mockMvc.perform(post(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/inventory/products")).andExpect(status().isUnauthorized());
		mockMvc.perform(get(path + "/{id}/history", fixture.productId())
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void publicCatalogShouldPaginateProductsAndVariants() throws Exception {
		CatalogActor actor = catalogActor();
		String host = storefrontHost(actor);
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shoes", true, false, null));
		Product alpha = productService.create(actor.id(), new ProductRequest(type.getId(), "A", "Alpha",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Black", null, null),
				new VariantRequest("White", null, null))));
		productService.create(actor.id(), new ProductRequest(type.getId(), "B", "Beta",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Blue", null, null))));
		String path = "/api/public/catalog/products";
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; }).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].name").value("Alpha"));
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; })
						.param("size", "1").param("page", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("Beta"));
		mockMvc.perform(get(path + "/{id}/variants", alpha.getId())
						.with(request -> { request.setServerName(host); return request; }).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2));
	}

	private String storefrontHost(CatalogActor actor) {
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		return storeRepository.findByMerchantId(merchantId).orElseThrow().getSubdomain().getValue() + ".localhost";
	}

	private UUID deliveryFixture(UUID store, UUID customer) {
		customerProfileService.updateProfile(store, customer,
				new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		return customerProfileService.create(store, customer, new CustomerProfileService.AddressRequest(
				"Ana", "01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP")).id();
	}

	@Test
	void customerAddressesShouldValidateAndEnforceOwnership() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor otherStore = catalogActor();
		String host = storefrontHost(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		customerAuthService.register(store, "Other", "other@example.com", "a-strong-password");
		String token = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		String other = customerAuthService.login(store, "other@example.com", "a-strong-password").accessToken();
		String address = """
				{"recipient":"Ana","postalCode":"01001000","street":"Rua A","number":"10",
				 "complement":null,"district":"Centro","city":"Sao Paulo","state":"SP"}
				""";
		mockMvc.perform(get("/api/public/customer/profile").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/public/customer/profile").header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content("{\"name\":\"Ana\",\"phone\":\"123\"}")
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isBadRequest());
		mockMvc.perform(put("/api/public/customer/profile").header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content("{\"name\":\"Ana Silva\",\"phone\":\"11987654321\"}")
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.email").value("ana@example.com"))
				.andExpect(jsonPath("$.phone").value("11987654321"));
		mockMvc.perform(post("/api/public/customer/addresses").header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content(address.replace("01001000", "01001-000"))
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/public/customer/addresses").header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content(address.replace("\"SP\"", "\"XX\""))
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isBadRequest());
		String created = mockMvc.perform(post("/api/public/customer/addresses").header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content(address).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.country").value("BR"))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		mockMvc.perform(get("/api/public/customer/addresses/{id}", id).header("Authorization", "Bearer " + other)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/public/customer/addresses/{id}", id).header("Authorization", "Bearer " + other)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/public/customer/addresses").header("Authorization", "Bearer " + token)
						.with(r -> { r.setServerName(storefrontHost(otherStore)); return r; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/public/customer/addresses").header("Authorization", "Bearer " + actor.token())
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/public/customer/addresses/{id}", id).header("Authorization", "Bearer " + token)
						.contentType(APPLICATION_JSON).content(address.replace("Rua A", "Rua B"))
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.street").value("Rua B"));
		mockMvc.perform(get("/api/public/customer/addresses").header("Authorization", "Bearer " + token)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mockMvc.perform(delete("/api/public/customer/addresses/{id}", id).header("Authorization", "Bearer " + token)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isNoContent());
	}

	@Test
	void customerAddressLimitShouldHoldDuringConcurrentCreation() throws Exception {
		CatalogActor actor = catalogActor();
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		var address = new CustomerProfileService.AddressRequest("Ana", "01001000", "Rua A", "10",
				null, "Centro", "Sao Paulo", "SP");
		for (int i = 0; i < 9; i++) {
			customerProfileService.create(store, customer.id(), address);
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> calls = List.of(
					executor.submit(() -> { start.await(); return customerProfileService.create(store, customer.id(), address); }),
					executor.submit(() -> { start.await(); return customerProfileService.create(store, customer.id(), address); }));
			start.countDown();
			assertOneSuccessOneConflict(calls);
			assertEquals(10, customerProfileService.addresses(store, customer.id()).size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void customerAuthenticationShouldEnforceStoreAndRotateRefreshTokens() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(owner);
		String otherHost = storefrontHost(other);
		String registration = "{\"name\":\"Ana\",\"email\":\" ANA@example.com \",\"password\":\"a-strong-password\"}";
		String valid = "{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"password\":\"a-strong-password\"}";
		mockMvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON).content(registration)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON).content(valid)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("ana@example.com"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
		mockMvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON).content(valid)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON)
						.content("{\"name\":\"Ana\",\"email\":\"ANA@EXAMPLE.COM\",\"password\":\"a-strong-password\"}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON).content(valid)
						.with(request -> { request.setServerName(otherHost); return request; }))
				.andExpect(status().isCreated());
		String login = "{\"email\":\"ana@example.com\",\"password\":\"a-strong-password\"}";
		String auth = mockMvc.perform(post("/api/public/customer-auth/login").contentType(APPLICATION_JSON).content(login)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(result -> assertEquals("no-store", result.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		String access = JsonPath.read(auth, "$.accessToken");
		String refresh = JsonPath.read(auth, "$.refreshToken");
		String refreshBody = "{\"refreshToken\":\"" + refresh + "\"}";
		mockMvc.perform(get("/api/public/customer/orders").header("Authorization", "Bearer " + access)
						.with(request -> { request.setServerName(otherHost); return request; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/public/customer/orders").header("Authorization", "Bearer " + owner.token())
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/public/customer/orders")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + access))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/public/customer-auth/refresh").contentType(APPLICATION_JSON).content(refreshBody)
						.with(request -> { request.setServerName(otherHost); return request; }))
				.andExpect(status().isUnauthorized());
		String rotated = mockMvc.perform(post("/api/public/customer-auth/refresh").contentType(APPLICATION_JSON).content(refreshBody)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		mockMvc.perform(post("/api/public/customer-auth/refresh").contentType(APPLICATION_JSON).content(refreshBody)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		String rotatedBody = "{\"refreshToken\":\"" + JsonPath.read(rotated, "$.refreshToken") + "\"}";
		mockMvc.perform(post("/api/public/customer-auth/logout").contentType(APPLICATION_JSON).content(rotatedBody)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/public/customer-auth/refresh").contentType(APPLICATION_JSON).content(rotatedBody)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/public/customer-auth/login").contentType(APPLICATION_JSON)
						.content("{\"email\":\"ana@example.com\",\"password\":\"wrong\"}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void customerRefreshShouldRotateOnlyOnceUnderConcurrency() throws Exception {
		CatalogActor actor = catalogActor();
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		String token = customerAuthService.login(store, "ana@example.com", "a-strong-password").refreshToken();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> calls = List.of(
					executor.submit(() -> { start.await(); return customerAuthService.refresh(store, token); }),
					executor.submit(() -> { start.await(); return customerAuthService.refresh(store, token); }));
			start.countDown();
			int success = 0;
			int rejected = 0;
			for (Future<?> call : calls) {
				try {
					call.get(30, TimeUnit.SECONDS);
					success++;
				} catch (ExecutionException exception) {
					assertInstanceOf(InvalidRefreshTokenException.class, exception.getCause());
					rejected++;
				}
			}
			assertEquals(1, success);
			assertEquals(1, rejected);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void pendingOrderShouldRequirePartialAcceptanceAndProtectCustomerData() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID addressId = deliveryFixture(store, customer.id());
		String customerToken = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		customerAuthService.register(store, "Other", "other@example.com", "a-strong-password");
		String anotherToken = customerAuthService.login(store, "other@example.com", "a-strong-password").accessToken();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, false, null));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SHIRT", "Shirt",
				"Cotton", new BigDecimal("10.00"), List.of(new VariantRequest("Black", null, null),
				new VariantRequest("White", null, new BigDecimal("15.00")))));
		UUID black = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = 'Black'",
				UUID.class, product.getId());
		UUID white = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = 'White'",
				UUID.class, product.getId());
		inventoryService.move(actor.id(), product.getId(), black, MovementType.ENTRY, 2, "Delivery", UUID.randomUUID());
		inventoryService.move(actor.id(), product.getId(), white, MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String cartJson = mockMvc.perform(post("/api/public/cart").with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(cartJson, "$.token");
		for (UUID variant : List.of(black, white)) {
			mockMvc.perform(put("/api/public/cart/items/{product}/{variant}", product.getId(), variant)
							.header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":1}")
							.with(request -> { request.setServerName(host); return request; }))
					.andExpect(status().isOk());
		}
		inventoryService.move(actor.id(), product.getId(), white, MovementType.EXIT, 1, "Adjustment", UUID.randomUUID());
		String checkout = mockMvc.perform(post("/api/public/checkout").header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String reservation = JsonPath.read(checkout, "$.id");
		String orderPath = "/api/public/checkout/" + reservation + "/orders";
		String body = "{\"acceptPartial\":true,\"addressId\":\"" + addressId + "\"}";
		mockMvc.perform(post(orderPath).header("Cart-Token", token).contentType(APPLICATION_JSON)
						.content(body).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + actor.token())
						.contentType(APPLICATION_JSON).content(body)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + customerToken)
						.contentType(APPLICATION_JSON).content(body)
						.with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isForbidden());
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + customerToken).contentType(APPLICATION_JSON)
						.content("{\"acceptPartial\":false,\"addressId\":\"" + addressId + "\"}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + customerToken).contentType(APPLICATION_JSON)
						.content("{}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isBadRequest());
		String order = mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + customerToken).contentType(APPLICATION_JSON)
						.content(body).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.customerName").value("Ana"))
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(10))
				.andExpect(jsonPath("$.items[0].variantId").value(black.toString()))
				.andExpect(result -> assertEquals("no-store", result.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(order, "$.id");
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + customerToken).contentType(APPLICATION_JSON)
						.content(body).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		mockMvc.perform(post(orderPath).header("Cart-Token", token).header("Authorization", "Bearer " + anotherToken).contentType(APPLICATION_JSON)
						.content(body)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		productService.updateVariantPrice(actor.id(), product.getId(), black, new BigDecimal("25.00"));
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + customerToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].unitPrice").value(10));
		String otherCart = mockMvc.perform(post("/api/public/cart").with(request -> { request.setServerName(host); return request; }))
				.andReturn().getResponse().getContentAsString();
		String otherToken = JsonPath.read(otherCart, "$.token");
		mockMvc.perform(get("/api/public/orders/{id}", id).header("Cart-Token", otherToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/public/orders/{id}", id).header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + anotherToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/public/customer/orders").header("Authorization", "Bearer " + customerToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + customerToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/public/orders/{id}", id).header("Cart-Token", token)
						.with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/orders/{id}", id)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/orders/{id}", id).header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/orders/{id}", id).header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.customerEmail").value("ana@example.com"))
				.andExpect(jsonPath("$.delivery.address.street").value("Rua A"))
				.andExpect(jsonPath("$.delivery.phone").value("11987654321"));
		mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + actor.token()).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].total").value(10));
		mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(post("/api/public/checkout/{id}/cancel", reservation).header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + customerToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		jdbcTemplate.update("DELETE FROM order_delivery_details WHERE order_id = ?", UUID.fromString(id));
		jdbcTemplate.update("UPDATE commerce_orders SET customer_id = NULL WHERE order_id = ?", UUID.fromString(id));
		mockMvc.perform(get("/api/public/orders/{id}", id).header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.delivery").doesNotExist());
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + customerToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		assertEquals(2, inventoryService.balance(actor.id(), product.getId(), black).quantity());
	}

	@Test
	void pendingOrderShouldExpireAndRejectCreationWithStaleCheckout() {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID addressId = deliveryFixture(store, customer.id());
		var secondCustomer = customerAuthService.register(store, "Other", "other@example.com", "a-strong-password");
		UUID foreignAddress = deliveryFixture(store, secondCustomer.id());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var first = checkoutReservationService.start(store, token);
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> orderService.create(store, token, first.id(), customer, false, foreignAddress)).getStatusCode().value());
		jdbcTemplate.update("UPDATE customer_accounts SET phone = NULL WHERE customer_id = ?", customer.id());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> orderService.create(store, token, first.id(), customer, false, addressId)).getStatusCode().value());
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		var order = orderService.create(store, token, first.id(), customer, false, addressId);
		customerProfileService.update(store, customer.id(), addressId, new CustomerProfileService.AddressRequest(
				"Ana", "01001000", "Rua Nova", "10", null, "Centro", "Sao Paulo", "SP"));
		customerProfileService.delete(store, customer.id(), addressId);
		assertEquals("Rua A", orderService.ownedOrder(store, customer.id(), order.id()).delivery().address().street());
		assertEquals("Rua A", orderService.create(store, token, first.id(), customer, false, addressId).delivery().address().street());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> orderService.create(store, token, first.id(), customer, false, foreignAddress)).getStatusCode().value());
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", first.id());
		assertEquals("EXPIRED", orderService.ownedOrder(store, customer.id(), order.id()).status());
		assertEquals("EXPIRED", orderService.create(store, token, first.id(), customer, false, addressId).status());
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?",
				TokenService.hashToken(token));
		assertEquals("EXPIRED", orderService.ownedOrder(store, customer.id(), order.id()).status());
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() + INTERVAL '1 day' WHERE token_hash = ?",
				TokenService.hashToken(token));
		var second = checkoutReservationService.start(store, token);
		assertNotEquals(first.id(), second.id());
		checkoutReservationService.cancel(store, token, second.id());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.create(
				store, token, second.id(), customer, false, addressId)).getStatusCode().value());
		var third = checkoutReservationService.start(store, token);
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", third.id());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.create(
				store, token, third.id(), customer, false, addressId)).getStatusCode().value());
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
				Integer.class, third.id()));
		assertEquals(1, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
	}

	@Test
	void pendingOrderCreationShouldBeIdempotentUnderConcurrency() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID addressId = deliveryFixture(store, customer.id());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var checkout = checkoutReservationService.start(store, token);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<OrderService.Order>> calls = List.of(
					executor.submit(() -> { start.await(); return orderService.create(store, token, checkout.id(), customer, false, addressId); }),
					executor.submit(() -> { start.await(); return orderService.create(store, token, checkout.id(), customer, false, addressId); }));
			start.countDown();
			assertEquals(calls.getFirst().get(30, TimeUnit.SECONDS).id(), calls.getLast().get(30, TimeUnit.SECONDS).id());
			assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
					Integer.class, checkout.id()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void pendingOrderAndCheckoutCancellationShouldSerialize() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID addressId = deliveryFixture(store, customer.id());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var checkout = checkoutReservationService.start(store, token);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<OrderService.Order> order = executor.submit(() -> {
				start.await(); return orderService.create(store, token, checkout.id(), customer, false, addressId);
			});
			Future<?> cancel = executor.submit(() -> {
				start.await(); return checkoutReservationService.cancel(store, token, checkout.id());
			});
			start.countDown();
			cancel.get(30, TimeUnit.SECONDS);
			try {
				var created = order.get(30, TimeUnit.SECONDS);
				assertEquals("CANCELLED", orderService.ownedOrder(store, customer.id(), created.id()).status());
			} catch (ExecutionException exception) {
				assertEquals(409, assertInstanceOf(ResponseStatusException.class, exception.getCause()).getStatusCode().value());
				assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
						Integer.class, checkout.id()));
			}
			assertEquals(1, shoppingCartService.get(store, token).items().getFirst().stockQuantity());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void checkoutShouldReserveAvailableItemsAndProtectInventory() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(actor);
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, false, null));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SHIRT", "Shirt",
				"Cotton", BigDecimal.TEN, List.of(new VariantRequest("Black", null, null),
				new VariantRequest("White", null, null))));
		UUID black = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = 'Black'",
				UUID.class, product.getId());
		UUID white = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color = 'White'",
				UUID.class, product.getId());
		inventoryService.move(actor.id(), product.getId(), black, MovementType.ENTRY, 5, "Delivery", UUID.randomUUID());
		inventoryService.move(actor.id(), product.getId(), white, MovementType.ENTRY, 2, "Delivery", UUID.randomUUID());
		String cartJson = mockMvc.perform(post("/api/public/cart").with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(cartJson, "$.token");
		for (UUID variant : List.of(black, white)) {
			mockMvc.perform(put("/api/public/cart/items/{product}/{variant}", product.getId(), variant)
							.header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":2}")
							.with(request -> { request.setServerName(host); return request; }))
					.andExpect(status().isOk());
		}
		inventoryService.move(actor.id(), product.getId(), white, MovementType.EXIT, 1, "Adjustment", UUID.randomUUID());
		String result = mockMvc.perform(post("/api/public/checkout").header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(result1 -> assertEquals("no-store", result1.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		String reservationId = JsonPath.read(result, "$.id");
		String expiry = JsonPath.read(result, "$.expiresAt");
		assertEquals(List.of(true), JsonPath.read(result, "$.items[?(@.variantId == '" + black + "')].reserved"));
		assertEquals(List.of(false), JsonPath.read(result, "$.items[?(@.variantId == '" + white + "')].reserved"));
		assertEquals(List.of(1), JsonPath.read(result, "$.items[?(@.variantId == '" + white + "')].availableQuantity"));
		mockMvc.perform(post("/api/public/checkout").header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(reservationId))
				.andExpect(jsonPath("$.expiresAt").value(expiry));
		mockMvc.perform(get("/api/public/catalog/products/{id}/variants", product.getId())
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.id == '%s')].quantity", black).value(hasItem(3)));
		mockMvc.perform(get("/api/public/cart").header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.variantId == '%s')].stockQuantity", black).value(hasItem(3)));
		mockMvc.perform(delete("/api/public/cart/items/{product}/{variant}", product.getId(), white)
						.header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(put("/api/public/cart/items/{product}/{variant}", product.getId(), white)
						.header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":1}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/public/checkout").header("Cart-Token", token)
						.with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isNotFound());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> inventoryService.move(actor.id(),
				product.getId(), black, MovementType.EXIT, 4, "Too much", UUID.randomUUID())).getStatusCode().value());
		var audit = inventoryService.openAudit(actor.id());
		var balance = inventoryService.balance(actor.id(), product.getId(), black);
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> inventoryService.count(actor.id(), audit.id(),
				product.getId(), black, balance.version(), 1, "Count")).getStatusCode().value());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> productService.setActive(actor.id(), product.getId(), false)).getStatusCode().value());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> productService.setVariantActive(actor.id(), product.getId(), black, false)).getStatusCode().value());
		productService.setVariantActive(actor.id(), product.getId(), white, false);
		mockMvc.perform(post("/api/public/checkout/{id}/cancel", UUID.randomUUID()).header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/public/checkout/{id}/cancel", reservationId).header("Cart-Token", token)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		mockMvc.perform(delete("/api/public/cart/items/{product}/{variant}", product.getId(), white)
						.header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk());
		inventoryService.move(actor.id(), product.getId(), black, MovementType.EXIT, 5, "After cancel", UUID.randomUUID());
		productService.setActive(actor.id(), product.getId(), false);
	}

	@Test
	void checkoutShouldExpireWithoutJobAndKeepCartEditableWhenUnavailable() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var first = checkoutReservationService.start(store, token);
		assertEquals("ACTIVE", first.status());
		assertEquals(1200, java.time.Duration.between(
				jdbcTemplate.queryForObject("SELECT created_at FROM checkout_reservations WHERE reservation_id = ?",
						java.sql.Timestamp.class, first.id()).toInstant(), first.expiresAt()).getSeconds());
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", first.id());
		assertEquals("EXPIRED", checkoutReservationService.get(store, token).status());
		assertEquals(1, shoppingCartService.get(store, token).items().getFirst().stockQuantity());
		shoppingCartService.remove(store, token, fixture.productId(), fixture.variantId());
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.EXIT, 1, "Adjustment", UUID.randomUUID());
		var unavailable = checkoutReservationService.start(store, token);
		assertEquals("UNAVAILABLE", unavailable.status());
		assertNull(unavailable.id());
		assertEquals(false, unavailable.items().getFirst().reserved());
		shoppingCartService.remove(store, token, fixture.productId(), fixture.variantId());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> checkoutReservationService.start(store, token)).getStatusCode().value());
	}

	@Test
	void checkoutShouldNotOversellDuringConcurrentLiveTraffic() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 30, "Delivery", UUID.randomUUID());
		List<String> tokens = new java.util.ArrayList<>();
		for (int i = 0; i < 60; i++) {
			String token = shoppingCartService.create(store).token();
			shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
			tokens.add(token);
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(60);
		try {
			List<Future<CheckoutReservationService.Checkout>> results = tokens.stream()
					.map(token -> executor.submit(() -> { start.await(); return checkoutReservationService.start(store, token); }))
					.toList();
			start.countDown();
			int active = 0;
			int unavailable = 0;
			for (var result : results) {
				switch (result.get(60, TimeUnit.SECONDS).status()) {
					case "ACTIVE" -> active++;
					case "UNAVAILABLE" -> unavailable++;
					default -> throw new AssertionError("Unexpected checkout status");
				}
			}
			assertEquals(30, active);
			assertEquals(30, unavailable);
			assertEquals(30, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			assertEquals(0, shoppingCartService.get(store, tokens.getFirst()).items().getFirst().stockQuantity());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void checkoutShouldSerializeRetriesAndStockAdjustments() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			Future<CheckoutReservationService.Checkout> first = executor.submit(() -> {
				start.await(); return checkoutReservationService.start(store, token);
			});
			Future<CheckoutReservationService.Checkout> second = executor.submit(() -> {
				start.await(); return checkoutReservationService.start(store, token);
			});
			Future<?> adjustment = executor.submit(() -> {
				start.await();
				return inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(),
						MovementType.EXIT, 1, "Concurrent sale", UUID.randomUUID());
			});
			start.countDown();
			var one = first.get(30, TimeUnit.SECONDS);
			var two = second.get(30, TimeUnit.SECONDS);
			assertEquals(one.status(), two.status());
			if (one.id() == null) {
				assertEquals("UNAVAILABLE", one.status());
				assertNull(two.id());
				adjustment.get(30, TimeUnit.SECONDS);
				assertEquals(0, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			} else {
				assertEquals("ACTIVE", one.status());
				assertEquals(one.id(), two.id());
				assertEquals(one.expiresAt(), two.expiresAt());
				ExecutionException exception = assertThrows(ExecutionException.class, () -> adjustment.get(30, TimeUnit.SECONDS));
				assertEquals(409, assertInstanceOf(ResponseStatusException.class, exception.getCause()).getStatusCode().value());
				assertEquals(1, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void checkoutShouldSerializeWithProductDeactivation() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CheckoutReservationService.Checkout> checkout = executor.submit(() -> {
				start.await(); return checkoutReservationService.start(store, token);
			});
			Future<?> deactivate = executor.submit(() -> {
				start.await(); return productService.setActive(actor.id(), fixture.productId(), false);
			});
			start.countDown();
			var result = checkout.get(30, TimeUnit.SECONDS);
			if (result.id() == null) {
				assertEquals("UNAVAILABLE", result.status());
				deactivate.get(30, TimeUnit.SECONDS);
			} else {
				assertEquals("ACTIVE", result.status());
				ExecutionException exception = assertThrows(ExecutionException.class, () -> deactivate.get(30, TimeUnit.SECONDS));
				assertEquals(409, assertInstanceOf(ResponseStatusException.class, exception.getCause()).getStatusCode().value());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void anonymousCartShouldValidateStockAndRetainUnavailableItems() throws Exception {
		CatalogActor actor = catalogActor();
		String host = storefrontHost(actor);
		InventoryFixture fixture = inventoryFixture(actor);
		String path = "/api/public/cart";
		String item = path + "/items/" + fixture.productId() + "/" + fixture.variantId();
		String created = mockMvc.perform(post(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(result -> assertEquals("no-store", result.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(created, "$.token");
		assertEquals(43, token.length());
		assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shopping_carts WHERE token_hash = ?",
				Integer.class, TokenService.hashToken(token)));
		mockMvc.perform(get(path).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mockMvc.perform(put(item).header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":1}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isConflict());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 5, "Delivery", UUID.randomUUID());
		mockMvc.perform(put(item).header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":3}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3))
				.andExpect(jsonPath("$.items[0].stockQuantity").value(5))
				.andExpect(jsonPath("$.items[0].available").value(true))
				.andExpect(jsonPath("$.items[0].price").value(10));
		mockMvc.perform(put(item).header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":3}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3));
		String anotherCart = mockMvc.perform(post(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String anotherToken = JsonPath.read(anotherCart, "$.token");
		mockMvc.perform(put(item).header("Cart-Token", anotherToken).contentType(APPLICATION_JSON).content("{\"quantity\":5}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(5));
		assertEquals(5, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.EXIT, 3, "Adjustment", UUID.randomUUID());
		mockMvc.perform(get(path).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3))
				.andExpect(jsonPath("$.items[0].stockQuantity").value(2))
				.andExpect(jsonPath("$.items[0].available").value(false));
		mockMvc.perform(put(item).header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":2}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].available").value(true));
		productService.updateVariantPrice(actor.id(), fixture.productId(), fixture.variantId(), new BigDecimal("12.50"));
		productService.setVariantActive(actor.id(), fixture.productId(), fixture.variantId(), false);
		mockMvc.perform(get(path).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].price").value(12.50))
				.andExpect(jsonPath("$.items[0].available").value(false));
		mockMvc.perform(delete(item).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mockMvc.perform(delete(item).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void anonymousCartShouldEnforceTenantTokenExpiryAndInputValidation() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(actor);
		InventoryFixture fixture = inventoryFixture(actor);
		InventoryFixture foreign = inventoryFixture(other);
		String path = "/api/public/cart";
		String created = mockMvc.perform(post(path).with(request -> { request.setServerName(host); return request; }))
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(created, "$.token");
		String item = path + "/items/" + fixture.productId() + "/" + fixture.variantId();
		mockMvc.perform(get(path).header("Cart-Token", token)
						.with(request -> { request.setServerName(storefrontHost(other)); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path).header("Cart-Token", "invalid").with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(put(path + "/items/{product}/{variant}", foreign.productId(), foreign.variantId())
						.header("Cart-Token", token).contentType(APPLICATION_JSON).content("{\"quantity\":1}")
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		for (String quantity : List.of("0", "-1", "1.5", "2147483648", "null")) {
			mockMvc.perform(put(item).header("Cart-Token", token).contentType(APPLICATION_JSON)
							.content("{\"quantity\":" + quantity + "}")
							.with(request -> { request.setServerName(host); return request; }))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post(path).header("X-Forwarded-Host", host)
						.with(request -> { request.setServerName("evil.example"); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(path).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isUnauthorized());
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?",
				TokenService.hashToken(token));
		mockMvc.perform(get(path).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete(item).header("Cart-Token", token).with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
	}

	@Test
	void anonymousCartShouldRenewOnlyAfterActualChanges() {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 5, "Delivery", UUID.randomUUID());
		var created = shoppingCartService.create(store);
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() + INTERVAL '1 day' WHERE token_hash = ?",
				TokenService.hashToken(created.token()));
		Instant oldExpiry = shoppingCartService.get(store, created.token()).expiresAt();
		assertEquals(oldExpiry, shoppingCartService.remove(store, created.token(), fixture.productId(), fixture.variantId()).expiresAt());
		Instant renewed = shoppingCartService.setQuantity(store, created.token(), fixture.productId(), fixture.variantId(), 1).expiresAt();
		assertTrue(renewed.isAfter(oldExpiry.plusSeconds(28 * 86400L)));
		assertEquals(renewed, shoppingCartService.setQuantity(store, created.token(), fixture.productId(), fixture.variantId(), 1).expiresAt());
	}

	@Test
	void anonymousCartShouldEnforceItemLimitUnderConcurrentChanges() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var cart = shoppingCartService.create(store);
		UUID cartId = jdbcTemplate.queryForObject("SELECT cart_id FROM shopping_carts WHERE token_hash = ?",
				UUID.class, TokenService.hashToken(cart.token()));
		jdbcTemplate.update("""
				INSERT INTO catalog_product_variants
				    (variant_id, store_id, product_id, color, size, price, active, created_at, created_by, updated_at, updated_by)
				SELECT gen_random_uuid(), ?, ?, 'test-' || n, NULL, NULL, true, clock_timestamp(), ?,
				       clock_timestamp(), ?
				FROM generate_series(1, 101) AS n
				""", store, fixture.productId(), actor.id(), actor.id());
		List<UUID> variants = jdbcTemplate.queryForList("""
				SELECT variant_id FROM catalog_product_variants WHERE product_id = ? AND color LIKE 'test-%'
				ORDER BY color
				""", UUID.class, fixture.productId());
		for (UUID variant : variants) {
			jdbcTemplate.update("INSERT INTO inventory_balances (variant_id, store_id, product_id, quantity, version) VALUES (?, ?, ?, 1, 0)",
					variant, store, fixture.productId());
		}
		for (UUID variant : variants.subList(0, 99)) {
			jdbcTemplate.update("INSERT INTO shopping_cart_items (store_id, cart_id, product_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
					store, cartId, fixture.productId(), variant);
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> results = List.of(
					executor.submit(() -> { start.await(); return shoppingCartService.setQuantity(
							store, cart.token(), fixture.productId(), variants.get(99), 1); }),
					executor.submit(() -> { start.await(); return shoppingCartService.setQuantity(
							store, cart.token(), fixture.productId(), variants.get(100), 1); }));
			start.countDown();
			assertOneSuccessOneConflict(results);
			assertEquals(100, shoppingCartService.get(store, cart.token()).items().size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void publicStoreResolverShouldValidateConfiguredDomains() {
		CatalogActor actor = catalogActor();
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		Store store = storeRepository.findByMerchantId(merchantId).orElseThrow();
		var resolver = new PublicStoreResolver(storeRepository, "Stores.Example.com");
		assertEquals(store.getId(), resolver.resolve(store.getSubdomain().getValue() + ".stores.example.com"));
		assertThrows(ResponseStatusException.class, () -> resolver.resolve(store.getSubdomain().getValue() + ".localhost"));
		assertThrows(ResponseStatusException.class, () -> resolver.resolve("nested." + store.getSubdomain().getValue() + ".stores.example.com"));
		assertThrows(IllegalArgumentException.class, () -> new PublicStoreResolver(storeRepository, "https://example.com"));
		assertThrows(IllegalArgumentException.class, () -> new PublicStoreResolver(storeRepository, "*.example.com"));
	}

	@Test
	void inventoryProductSummaryShouldPaginateAndSumBeyondIntegerRange() throws Exception {
		CatalogActor actor = catalogActor();
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Items", true, false, null));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SKU1", "Alpha",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Black", null, null),
				new VariantRequest("White", null, null))));
		List<UUID> variants = jdbcTemplate.queryForList(
				"SELECT variant_id FROM catalog_product_variants WHERE product_id = ?", UUID.class, product.getId());
		for (UUID variant : variants) {
			inventoryService.move(actor.id(), product.getId(), variant, MovementType.ENTRY,
					Integer.MAX_VALUE, "Delivery", UUID.randomUUID());
		}
		productService.create(actor.id(), new ProductRequest(type.getId(), "SKU2", "Beta",
				"Description", BigDecimal.TEN, List.of(new VariantRequest("Blue", null, null))));
		String token = "Bearer " + actor.token();
		mockMvc.perform(get("/api/inventory/products").header("Authorization", token).param("size", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].name").value("Alpha"))
				.andExpect(jsonPath("$.content[0].physicalQuantity").value(4294967294L))
				.andExpect(jsonPath("$.content[0].enabledQuantity").value(4294967294L));
		mockMvc.perform(get("/api/inventory/products").header("Authorization", token).param("size", "1").param("page", "1"))
				.andExpect(jsonPath("$.content[0].name").value("Beta"))
				.andExpect(jsonPath("$.content[0].physicalQuantity").value(0));
	}

	@Test
	void inventoryShouldTrackMovementsAndFullAuditHistory() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		String token = "Bearer " + actor.token();
		String path = "/api/inventory/products/" + fixture.productId() + "/variants/" + fixture.variantId();
		mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(0)).andExpect(jsonPath("$.version").value(0));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()).contentType(APPLICATION_JSON)
						.content("{\"type\":\"ENTRY\",\"quantity\":10,\"reason\":\"Delivery\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.beforeQuantity").value(0))
				.andExpect(jsonPath("$.afterQuantity").value(10));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()).contentType(APPLICATION_JSON)
						.content("{\"type\":\"EXIT\",\"quantity\":2,\"reason\":\"Damaged\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.afterQuantity").value(8));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()).contentType(APPLICATION_JSON)
						.content("{\"type\":\"EXIT\",\"quantity\":9,\"reason\":\"Damaged\"}"))
				.andExpect(status().isConflict());
		String auditId = JsonPath.read(mockMvc.perform(post("/api/inventory/audits").header("Authorization", token))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.openedBy").value(actor.id().toString()))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/inventory/audits").header("Authorization", token)).andExpect(status().isConflict());
		String count = "{\"productId\":\"%s\",\"variantId\":\"%s\",\"expectedVersion\":2,\"quantity\":7,\"reason\":\"Physical count\"}"
				.formatted(fixture.productId(), fixture.variantId());
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content(count))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.beforeQuantity").value(8))
				.andExpect(jsonPath("$.afterQuantity").value(7)).andExpect(jsonPath("$.auditId").value(auditId));
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content(count)).andExpect(status().isConflict());
		mockMvc.perform(get(path).header("Authorization", token)).andExpect(jsonPath("$.quantity").value(7))
				.andExpect(jsonPath("$.version").value(3));
		mockMvc.perform(get(path + "/movements").header("Authorization", token).param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2));
		mockMvc.perform(get("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].actorId").value(actor.id().toString()));
		String recount = "{\"productId\":\"%s\",\"variantId\":\"%s\",\"expectedVersion\":3,\"quantity\":7,\"reason\":\"Recount\"}"
				.formatted(fixture.productId(), fixture.variantId());
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content(recount)).andExpect(status().isCreated());
		mockMvc.perform(get("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token))
				.andExpect(jsonPath("$.totalElements").value(2));
		mockMvc.perform(post("/api/inventory/audits/{id}/close", auditId).header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.closedBy").value(actor.id().toString()))
				.andExpect(jsonPath("$.closedAt").isNotEmpty());
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content(count)).andExpect(status().isConflict());
		mockMvc.perform(post("/api/inventory/audits").header("Authorization", token)).andExpect(status().isCreated());
	}

	@Test
	void inventoryShouldValidateRequestsAndEnforceTenantIsolation() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		InventoryFixture fixture = inventoryFixture(owner);
		String token = "Bearer " + owner.token();
		String otherToken = "Bearer " + other.token();
		String path = "/api/inventory/products/" + fixture.productId() + "/variants/" + fixture.variantId();
		for (String body : List.of("{\"type\":\"ENTRY\",\"quantity\":0,\"reason\":\"test\"}",
				"{\"type\":\"EXIT\",\"quantity\":-1,\"reason\":\"test\"}",
				"{\"type\":\"ENTRY\",\"quantity\":1.5,\"reason\":\"test\"}",
				"{\"type\":\"ENTRY\",\"quantity\":2147483648,\"reason\":\"test\"}",
				"{\"type\":\"ENTRY\",\"quantity\":1,\"reason\":\" \"}",
				"{\"type\":\"COUNT\",\"quantity\":1,\"reason\":\"test\"}")) {
			mockMvc.perform(post(path + "/movements").header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())
							.contentType(APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(get(path).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(post(path + "/movements").header("Authorization", otherToken).header("Idempotency-Key", UUID.randomUUID()).contentType(APPLICATION_JSON)
						.content("{\"type\":\"ENTRY\",\"quantity\":1,\"reason\":\"test\"}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(path + "/movements").header("Authorization", otherToken)).andExpect(status().isNotFound());
		UUID merchantId = merchantAccountRepository.findById(owner.id()).orElseThrow().getMerchantId();
		mockMvc.perform(get(path).header("Authorization", "Bearer " + tokenWithClaims(other.id(), merchantId)))
				.andExpect(status().isNotFound());
		String auditId = JsonPath.read(mockMvc.perform(post("/api/inventory/audits").header("Authorization", token))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", token)
						.contentType(APPLICATION_JSON).content("{\"productId\":\"%s\",\"variantId\":\"%s\",\"expectedVersion\":0,\"quantity\":1.5,\"reason\":\"test\"}"
								.formatted(fixture.productId(), fixture.variantId())))
				.andExpect(status().isBadRequest());
		String otherAuditId = JsonPath.read(mockMvc.perform(post("/api/inventory/audits").header("Authorization", otherToken))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", otherAuditId).header("Authorization", otherToken)
						.contentType(APPLICATION_JSON).content("{\"productId\":\"%s\",\"variantId\":\"%s\",\"expectedVersion\":0,\"quantity\":1,\"reason\":\"test\"}"
								.formatted(fixture.productId(), fixture.variantId())))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/inventory/audits/{id}", auditId).header("Authorization", otherToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/inventory/audits/{id}/counts", auditId).header("Authorization", otherToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/inventory/audits/{id}/close", auditId).header("Authorization", otherToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/inventory/audits/{id}/counts", auditId).header("Authorization", otherToken)
						.contentType(APPLICATION_JSON).content("{\"productId\":\"%s\",\"variantId\":\"%s\",\"expectedVersion\":0,\"quantity\":1,\"reason\":\"test\"}"
								.formatted(fixture.productId(), fixture.variantId())))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/inventory/audits").header("Authorization", token).param("size", "101"))
				.andExpect(status().isBadRequest());
		productService.setActive(owner.id(), fixture.productId(), false);
		mockMvc.perform(post(path + "/movements").header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()).contentType(APPLICATION_JSON)
						.content("{\"type\":\"ENTRY\",\"quantity\":1,\"reason\":\"test\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void inventoryShouldSerializeConcurrentExitsAndAuditOpenings() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> exits = List.of(
					executor.submit(() -> { start.await(); return inventoryService.move(actor.id(), fixture.productId(),
							fixture.variantId(), MovementType.EXIT, 1, "Damage", UUID.randomUUID()); }),
					executor.submit(() -> { start.await(); return inventoryService.move(actor.id(), fixture.productId(),
							fixture.variantId(), MovementType.EXIT, 1, "Damage", UUID.randomUUID()); }));
			start.countDown();
			assertOneSuccessOneConflict(exits);
			assertEquals(0, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			CountDownLatch auditStart = new CountDownLatch(1);
			List<Future<?>> audits = List.of(executor.submit(() -> { auditStart.await(); return inventoryService.openAudit(actor.id()); }),
					executor.submit(() -> { auditStart.await(); return inventoryService.openAudit(actor.id()); }));
			auditStart.countDown();
			assertOneSuccessOneConflict(audits);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void inventoryMovementRetriesShouldReturnOriginalResultWithoutChangingStock() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		InventoryFixture otherFixture = inventoryFixture(other);
		String token = "Bearer " + actor.token();
		String path = "/api/inventory/products/" + fixture.productId() + "/variants/" + fixture.variantId() + "/movements";
		String request = "{\"type\":\"ENTRY\",\"quantity\":5,\"reason\":\"Delivery\"}";
		UUID key = UUID.randomUUID();
		mockMvc.perform(post(path).header("Authorization", token).contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", "invalid")
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
		String first = mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String retry = mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertEquals(first, retry);
		for (String changed : List.of("{\"type\":\"ENTRY\",\"quantity\":6,\"reason\":\"Delivery\"}",
				"{\"type\":\"EXIT\",\"quantity\":5,\"reason\":\"Delivery\"}",
				"{\"type\":\"ENTRY\",\"quantity\":5,\"reason\":\"Different\"}")) {
			mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
							.contentType(APPLICATION_JSON).content(changed)).andExpect(status().isConflict());
		}
		UUID typeId = jdbcTemplate.queryForObject("SELECT item_type_id FROM catalog_products WHERE product_id = ?",
				UUID.class, fixture.productId());
		Product second = productService.create(actor.id(), new ProductRequest(typeId, "SKU-2", "Second",
				"Description", BigDecimal.TEN, List.of(new VariantRequest(null, null, null))));
		UUID secondVariant = jdbcTemplate.queryForObject("SELECT variant_id FROM catalog_product_variants WHERE product_id = ?",
				UUID.class, second.getId());
		String secondPath = "/api/inventory/products/" + second.getId() + "/variants/" + secondVariant + "/movements";
		mockMvc.perform(post(secondPath).header("Authorization", token).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isConflict());
		String otherPath = "/api/inventory/products/" + otherFixture.productId() + "/variants/" + otherFixture.variantId() + "/movements";
		mockMvc.perform(post(otherPath).header("Authorization", "Bearer " + other.token()).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isCreated());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + other.token()).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isNotFound());
		UUID exitKey = UUID.randomUUID();
		String exit = "{\"type\":\"EXIT\",\"quantity\":2,\"reason\":\"Damaged\"}";
		String exitFirst = mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", exitKey)
						.contentType(APPLICATION_JSON).content(exit))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String exitRetry = mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", exitKey)
						.contentType(APPLICATION_JSON).content(exit))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertEquals(exitFirst, exitRetry);
		assertEquals(3, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
		assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE variant_id = ?",
				Integer.class, fixture.variantId()));
		assertEquals(first, mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", key)
						.contentType(APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		UUID failedKey = UUID.randomUUID();
		String largeExit = "{\"type\":\"EXIT\",\"quantity\":6,\"reason\":\"Damaged\"}";
		mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", failedKey)
						.contentType(APPLICATION_JSON).content(largeExit)).andExpect(status().isConflict());
		mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())
						.contentType(APPLICATION_JSON).content(request)).andExpect(status().isCreated());
		mockMvc.perform(post(path).header("Authorization", token).header("Idempotency-Key", failedKey)
						.contentType(APPLICATION_JSON).content(largeExit)).andExpect(status().isCreated());
		assertEquals(2, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
	}

	@Test
	void simultaneousInventoryMovementRetriesShouldApplyOnlyOnce() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID key = UUID.randomUUID();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<InventoryService.Movement> first = executor.submit(() -> {
				start.await();
				return inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 5, "Delivery", key);
			});
			Future<InventoryService.Movement> second = executor.submit(() -> {
				start.await();
				return inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 5, "Delivery", key);
			});
			start.countDown();
			assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
			assertEquals(5, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE variant_id = ?",
					Integer.class, fixture.variantId()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void inventoryAuditShouldRejectAStaleCountEvenIfQuantityReturnsToSameValue() {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		var audit = inventoryService.openAudit(actor.id());
		var observed = inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.EXIT, 1, "Damage", UUID.randomUUID());
		ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () -> inventoryService.count(
				actor.id(), audit.id(), fixture.productId(), fixture.variantId(), observed.version(), 5, "Count"));
		assertEquals(409, conflict.getStatusCode().value());
		assertEquals(0, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
	}

	@Test
	void inventoryAuditShouldRejectConcurrentCountsOfTheSameVersion() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		var audit = inventoryService.openAudit(actor.id());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> counts = List.of(
					executor.submit(() -> { start.await(); return inventoryService.count(actor.id(), audit.id(),
							fixture.productId(), fixture.variantId(), 0, 5, "First count"); }),
					executor.submit(() -> { start.await(); return inventoryService.count(actor.id(), audit.id(),
							fixture.productId(), fixture.variantId(), 0, 6, "Second count"); }));
			start.countDown();
			assertOneSuccessOneConflict(counts);
			assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE audit_id = ?",
					Integer.class, audit.id()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void inventoryMovementAndBalanceShouldRollbackTogether() {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		jdbcTemplate.execute("""
				CREATE OR REPLACE FUNCTION commerce_test_fail_inventory_history()
				RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.reason = 'force-inventory-failure' THEN
						RAISE EXCEPTION 'Simulated inventory history failure';
					END IF;
					RETURN NEW;
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER commerce_test_fail_inventory_history
				BEFORE INSERT ON inventory_movements
				FOR EACH ROW EXECUTE FUNCTION commerce_test_fail_inventory_history()
				""");
		try {
			UUID key = UUID.randomUUID();
			assertThrows(DataAccessException.class, () -> inventoryService.move(actor.id(), fixture.productId(),
					fixture.variantId(), MovementType.ENTRY, 10, "force-inventory-failure", key));
			assertEquals(0, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_balances WHERE variant_id = ?",
					Integer.class, fixture.variantId()));
			assertEquals(0, jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM inventory_movement_requests WHERE idempotency_key = ?", Integer.class, key));
			assertEquals(10, inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(),
					MovementType.ENTRY, 10, "Retry", key).afterQuantity());
		} finally {
			jdbcTemplate.execute("DROP TRIGGER commerce_test_fail_inventory_history ON inventory_movements");
			jdbcTemplate.execute("DROP FUNCTION commerce_test_fail_inventory_history()");
		}
	}

	private void assertOneSuccessOneConflict(List<Future<?>> results) throws Exception {
		int succeeded = 0;
		int conflicted = 0;
		for (Future<?> result : results) {
			try {
				result.get(15, TimeUnit.SECONDS);
				succeeded++;
			} catch (ExecutionException exception) {
				ResponseStatusException cause = assertInstanceOf(ResponseStatusException.class, exception.getCause());
				assertEquals(409, cause.getStatusCode().value());
				conflicted++;
			}
		}
		assertEquals(1, succeeded);
		assertEquals(1, conflicted);
	}

	private record InventoryFixture(UUID productId, UUID variantId) { }

	private InventoryFixture inventoryFixture(CatalogActor actor) {
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Simple", false, false, null));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SKU", "Product",
				"Description", BigDecimal.TEN, List.of(new VariantRequest(null, null, null))));
		UUID variant = jdbcTemplate.queryForObject(
				"SELECT variant_id FROM catalog_product_variants WHERE product_id = ?", UUID.class, product.getId());
		return new InventoryFixture(product.getId(), variant);
	}

	private Product createProductAfterStart(CountDownLatch start, UUID actor, UUID type, String sku) throws InterruptedException {
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent product writes did not start");
		}
		return productService.create(actor, new ProductRequest(type, sku, "Product", "Description", BigDecimal.TEN,
				List.of(new VariantRequest(null, null, null))));
	}

	private record CatalogActor(UUID id, String token) {
	}

	private String tokenWithClaims(UUID subject, UUID merchantId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("commerce-platform")
				.subject(subject.toString()).issuedAt(now).expiresAt(now.plusSeconds(60))
				.claim("merchant_id", merchantId.toString()).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private CatalogActor catalogActor() {
		String email = uniqueEmail();
		String password = "a-strong-local-password";
		registerMerchant.execute(new RegisterMerchantCommand("Merchant", "Store", uniqueSubdomain(), email, password));
		UUID id = merchantAccountRepository.findByEmail(email).orElseThrow().getId();
		return new CatalogActor(id, authenticateMerchant.execute(email, password).accessToken());
	}

	private ItemType createItemAfterStart(CountDownLatch start, UUID actorId, String name) throws InterruptedException {
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent item type writes did not start");
		}
		return itemTypeService.create(actorId, new ItemTypeValues(name, true, true, SizeFormat.LETTER));
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
	void shouldCreateMerchantStoreAndAccountAtomicallyThroughPublicApi() throws Exception {
		String subdomain = uniqueSubdomain();
		String email = uniqueEmail();

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": "Urban Store LTDA",
								  "storeName": "Urban Store",
								  "subdomain": "%s",
								  "email": "%s",
								  "password": "a-strong-local-password"
								}
								""".formatted(subdomain, email)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.merchantId").isNotEmpty())
				.andExpect(jsonPath("$.storeId").isNotEmpty())
				.andExpect(jsonPath("$.merchantName").value("Urban Store LTDA"))
				.andExpect(jsonPath("$.storeName").value("Urban Store"))
				.andExpect(jsonPath("$.subdomain").value(subdomain));

		var account = merchantAccountRepository.findByEmail(email).orElseThrow();
		assertNotNull(account.getPasswordHash());
		assertTrue(!"a-strong-local-password".equals(account.getPasswordHash()));
		assertEquals(1, jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM merchants m
				JOIN stores s ON s.merchant_id = m.merchant_id
				JOIN merchant_accounts a ON a.merchant_id = m.merchant_id
				WHERE s.subdomain = ? AND a.email = ?
				""",
				Integer.class,
				subdomain,
				email
		));
	}

	@Test
	void shouldRollbackMerchantAndStoreWhenAccountPersistenceFails() {
		long existingMerchants = merchantRepository.count();
		long existingStores = storeRepository.count();
		long existingAccounts = merchantAccountRepository.count();
		createFailingAccountTrigger();

		try {
			assertThrows(
					DataAccessException.class,
					() -> registerMerchant.execute(new RegisterMerchantCommand(
							"Rollback Merchant",
							"Rollback Store",
							uniqueSubdomain(),
							uniqueEmail(),
							"a-strong-local-password"
					))
			);

			assertEquals(existingMerchants, merchantRepository.count());
			assertEquals(existingStores, storeRepository.count());
			assertEquals(existingAccounts, merchantAccountRepository.count());
		} finally {
			dropFailingAccountTrigger();
		}
	}

	@Test
	void shouldRejectDuplicateMerchantAccountEmail() throws Exception {
		String email = uniqueEmail();
		String firstRequest = merchantRegistrationRequest(uniqueSubdomain(), email);
		mockMvc.perform(post("/api/merchants").contentType(APPLICATION_JSON).content(firstRequest))
				.andExpect(status().isCreated());

		long existingMerchants = merchantRepository.count();
		long existingStores = storeRepository.count();
		long existingAccounts = merchantAccountRepository.count();

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content(merchantRegistrationRequest(uniqueSubdomain(), email.toUpperCase())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertEquals(existingMerchants, merchantRepository.count());
		assertEquals(existingStores, storeRepository.count());
		assertEquals(existingAccounts, merchantAccountRepository.count());
	}

	@Test
	void databaseShouldEnforceUniqueMerchantAccountEmail() {
		String email = uniqueEmail();
		Merchant firstMerchant = merchantRepository.saveAndFlush(Merchant.create("First " + UUID.randomUUID()));
		Merchant secondMerchant = merchantRepository.saveAndFlush(Merchant.create("Second " + UUID.randomUUID()));

		merchantAccountRepository.saveAndFlush(MerchantAccount.create(firstMerchant.getId(), email, "hash-one"));

		assertThrows(
				DataIntegrityViolationException.class,
				() -> merchantAccountRepository.saveAndFlush(
						MerchantAccount.create(secondMerchant.getId(), email, "hash-two")
				)
		);
	}

	@Test
	void shouldNotAllowPasswordLoginForAccountWithoutLocalCredential() throws Exception {
		String email = uniqueEmail();
		Merchant merchant = merchantRepository.saveAndFlush(Merchant.create("External Login Merchant"));
		merchantAccountRepository.saveAndFlush(MerchantAccount.create(merchant.getId(), email, null));

		mockMvc.perform(post("/api/auth/login")
						.contentType(APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"a-strong-local-password"}
								""".formatted(email)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Authentication request is invalid."));
	}

	@Test
	void shouldLoginRotateRefreshTokenAndLogout() throws Exception {
		String email = uniqueEmail();
		String password = "a-strong-local-password";
		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content(merchantRegistrationRequest(uniqueSubdomain(), email, password)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/auth/login")
						.contentType(APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"wrong-password"}
								""".formatted(email)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Authentication request is invalid."));

		mockMvc.perform(post("/api/auth/login")
						.contentType(APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"wrong-password"}
								""".formatted(uniqueEmail())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Authentication request is invalid."));

		var loginResponse = mockMvc.perform(post("/api/auth/login")
						.contentType(APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"%s"}
								""".formatted(email, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(TokenService.ACCESS_TOKEN_SECONDS))
				.andReturn();
		String loginBody = loginResponse.getResponse().getContentAsString();
		String accessToken = JsonPath.read(loginBody, "$.accessToken");
		String refreshToken = JsonPath.read(loginBody, "$.refreshToken");

		var storedToken = refreshTokenRepository.findByTokenHash(TokenService.hashToken(refreshToken))
				.orElseThrow();
		assertNotNull(storedToken.getExpiresAt());
		assertNull(storedToken.getRevokedAt());
		assertEquals(401, mockMvc.perform(
						org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/merchants")
				).andReturn().getResponse().getStatus());
		assertEquals(405, mockMvc.perform(
						org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/merchants")
								.header("Authorization", "Bearer " + accessToken)
				).andReturn().getResponse().getStatus());

		var refreshResponse = mockMvc.perform(post("/api/auth/refresh")
						.contentType(APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refreshToken)))
				.andExpect(status().isOk())
				.andReturn();
		String rotatedRefreshToken = JsonPath.read(
				refreshResponse.getResponse().getContentAsString(),
				"$.refreshToken"
		);
		assertNotEquals(refreshToken, rotatedRefreshToken);
		assertNotNull(refreshTokenRepository.findByTokenHash(TokenService.hashToken(refreshToken))
				.orElseThrow().getRevokedAt());

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(refreshToken)))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/auth/logout")
						.contentType(APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(rotatedRefreshToken)))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(APPLICATION_JSON)
						.content("""
								{"refreshToken":"%s"}
								""".formatted(rotatedRefreshToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void shouldAllowOnlyOneConcurrentRefreshForTheSameToken() throws Exception {
		String email = uniqueEmail();
		String password = "a-strong-local-password";
		registerMerchant.execute(new RegisterMerchantCommand(
				"Concurrent Merchant",
				"Concurrent Store",
				uniqueSubdomain(),
				email,
				password
		));
		String refreshToken = authenticateMerchant.execute(email, password).refreshToken();
		CountDownLatch startRefreshes = new CountDownLatch(1);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TokenPair> firstRefresh = executor.submit(
					() -> refreshAfterStart(startRefreshes, refreshToken)
			);
			Future<TokenPair> secondRefresh = executor.submit(
					() -> refreshAfterStart(startRefreshes, refreshToken)
			);
			startRefreshes.countDown();

			int successfulRefreshes = 0;
			int rejectedRefreshes = 0;
			for (Future<TokenPair> refresh : List.of(firstRefresh, secondRefresh)) {
				try {
					assertNotNull(refresh.get(15, TimeUnit.SECONDS));
					successfulRefreshes++;
				} catch (ExecutionException exception) {
					assertInstanceOf(InvalidRefreshTokenException.class, exception.getCause());
					rejectedRefreshes++;
				}
			}

			assertEquals(1, successfulRefreshes);
			assertEquals(1, rejectedRefreshes);
			assertEquals(1, jdbcTemplate.queryForObject(
					"""
					SELECT COUNT(*)
					FROM refresh_tokens t
					JOIN merchant_accounts a ON a.account_id = t.account_id
					WHERE a.email = ? AND t.revoked_at IS NULL
					""",
					Integer.class,
					email
			));
		} finally {
			executor.shutdownNow();
		}
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
		String email = uniqueEmail();

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": "Urban Store LTDA",
								  "storeName": "Urban Store",
								  "subdomain": "%s",
								  "email": "%s",
								  "password": "a-strong-local-password"
								}
								""".formatted(subdomain, email)))
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
				.andExpect(jsonPath("$.errors").value(hasItem("merchantName: must not be blank")));

		mockMvc.perform(post("/api/merchants")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "merchantName": "Urban Store LTDA",
								  "storeName": "Urban Store",
								  "subdomain": "invalid_subdomain",
								  "email": "%s",
								  "password": "a-strong-local-password"
								}
								""".formatted(uniqueEmail())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		assertEquals(existingMerchants, merchantRepository.count());
		assertEquals(existingStores, storeRepository.count());
	}

	@Test
	void shouldReturnConflictForDuplicateMerchantApiSubdomain() throws Exception {
		String subdomain = uniqueSubdomain();
		String firstRequest = """
				{
				  "merchantName": "Urban Store LTDA",
				  "storeName": "Urban Store",
				  "subdomain": "%s",
				  "email": "%s",
				  "password": "a-strong-local-password"
				}
				""".formatted(subdomain, uniqueEmail());
		String secondRequest = merchantRegistrationRequest(subdomain, uniqueEmail());

		mockMvc.perform(post("/api/merchants").contentType(APPLICATION_JSON).content(firstRequest))
				.andExpect(status().isCreated());

		long merchantCountAfterFirstRequest = merchantRepository.count();
		long storeCountAfterFirstRequest = storeRepository.count();

		mockMvc.perform(post("/api/merchants").contentType(APPLICATION_JSON).content(secondRequest))
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

	private TokenPair refreshAfterStart(CountDownLatch startRefreshes, String refreshToken)
			throws InterruptedException {
		if (!startRefreshes.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent refresh requests did not start");
		}
		return refreshAuthentication.execute(refreshToken);
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

	private void createFailingAccountTrigger() {
		dropFailingAccountTrigger();
		jdbcTemplate.execute("""
				CREATE OR REPLACE FUNCTION commerce_test_fail_account_insert()
				RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					RAISE EXCEPTION 'Simulated account insert failure';
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER commerce_test_fail_account_insert
				BEFORE INSERT ON merchant_accounts
				FOR EACH ROW EXECUTE FUNCTION commerce_test_fail_account_insert()
				""");
	}

	private void dropFailingAccountTrigger() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS commerce_test_fail_account_insert ON merchant_accounts");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS commerce_test_fail_account_insert()");
	}

	private String merchantRegistrationRequest(String subdomain, String email) {
		return merchantRegistrationRequest(subdomain, email, "a-strong-local-password");
	}

	private String merchantRegistrationRequest(String subdomain, String email, String password) {
		return """
				{
				  "merchantName": "Urban Store LTDA",
				  "storeName": "Urban Store",
				  "subdomain": "%s",
				  "email": "%s",
				  "password": "%s"
				}
				""".formatted(subdomain, email, password);
	}

	private String uniqueEmail() {
		return "merchant-" + UUID.randomUUID() + "@example.test";
	}

	private String uniqueSubdomain() {
		return "store-" + UUID.randomUUID().toString().substring(0, 8);
	}
}
