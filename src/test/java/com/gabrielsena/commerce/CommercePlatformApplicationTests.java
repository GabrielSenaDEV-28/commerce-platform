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
import com.gabrielsena.commerce.inventory.application.InventoryService;
import com.gabrielsena.commerce.inventory.application.InventoryService.MovementType;
import com.gabrielsena.commerce.store.StoreRepository;
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
	void inventoryShouldTrackMovementsAndFullAuditHistory() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		String token = "Bearer " + actor.token();
		String path = "/api/inventory/products/" + fixture.productId() + "/variants/" + fixture.variantId();
		mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(0)).andExpect(jsonPath("$.version").value(0));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).contentType(APPLICATION_JSON)
						.content("{\"type\":\"ENTRY\",\"quantity\":10,\"reason\":\"Delivery\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.beforeQuantity").value(0))
				.andExpect(jsonPath("$.afterQuantity").value(10));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).contentType(APPLICATION_JSON)
						.content("{\"type\":\"EXIT\",\"quantity\":2,\"reason\":\"Damaged\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.afterQuantity").value(8));
		mockMvc.perform(post(path + "/movements").header("Authorization", token).contentType(APPLICATION_JSON)
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
			mockMvc.perform(post(path + "/movements").header("Authorization", token)
							.contentType(APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(get(path).header("Authorization", otherToken)).andExpect(status().isNotFound());
		mockMvc.perform(post(path + "/movements").header("Authorization", otherToken).contentType(APPLICATION_JSON)
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
		mockMvc.perform(post(path + "/movements").header("Authorization", token).contentType(APPLICATION_JSON)
						.content("{\"type\":\"ENTRY\",\"quantity\":1,\"reason\":\"test\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void inventoryShouldSerializeConcurrentExitsAndAuditOpenings() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> exits = List.of(
					executor.submit(() -> { start.await(); return inventoryService.move(actor.id(), fixture.productId(),
							fixture.variantId(), MovementType.EXIT, 1, "Damage"); }),
					executor.submit(() -> { start.await(); return inventoryService.move(actor.id(), fixture.productId(),
							fixture.variantId(), MovementType.EXIT, 1, "Damage"); }));
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
	void inventoryAuditShouldRejectAStaleCountEvenIfQuantityReturnsToSameValue() {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		var audit = inventoryService.openAudit(actor.id());
		var observed = inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery");
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.EXIT, 1, "Damage");
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
			assertThrows(DataAccessException.class, () -> inventoryService.move(actor.id(), fixture.productId(),
					fixture.variantId(), MovementType.ENTRY, 10, "force-inventory-failure"));
			assertEquals(0, inventoryService.balance(actor.id(), fixture.productId(), fixture.variantId()).quantity());
			assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_balances WHERE variant_id = ?",
					Integer.class, fixture.variantId()));
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
