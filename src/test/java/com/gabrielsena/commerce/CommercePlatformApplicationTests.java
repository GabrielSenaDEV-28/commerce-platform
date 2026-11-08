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
import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.application.ItemTypeService;
import com.gabrielsena.commerce.catalog.application.ProductService;
import com.gabrielsena.commerce.catalog.application.ProductPhotoStorage;
import com.gabrielsena.commerce.catalog.application.ProductPhotoService;
import com.gabrielsena.commerce.catalog.api.ProductRequest;
import com.gabrielsena.commerce.catalog.api.VariantRequest;
import com.gabrielsena.commerce.catalog.api.VariantShippingRequest;
import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.ItemTypeValues;
import com.gabrielsena.commerce.catalog.domain.Product;
import com.gabrielsena.commerce.catalog.domain.SizeFormat;
import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.checkout.application.CheckoutReservationService;
import com.gabrielsena.commerce.order.application.OrderService;
import com.gabrielsena.commerce.order.application.OrderCheckoutService;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.inventory.application.InventoryService;
import com.gabrielsena.commerce.inventory.application.InventoryService.MovementType;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioOAuthClient;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioOAuthService;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService;
import com.gabrielsena.commerce.shipping.api.ShippingEstimateController;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
	private OrderCheckoutService orderCheckoutService;

	@Autowired
	private ShippingEstimateService shippingEstimateService;

	@MockitoSpyBean
	private MelhorEnvioOAuthService shippingAuthorization;

	@MockitoSpyBean
	private MelhorEnvioClient shippingProvider;

	@MockitoSpyBean
	private ProductPhotoStorage photoStorage;

	@Autowired
	private ProductPhotoService photoService;

	@Autowired
	private CustomerAuthService customerAuthService;

	@Autowired
	private CustomerProfileService customerProfileService;

	@Autowired
	private StoreShippingSettingsService storeShippingSettingsService;

	@Autowired
	private CatalogStoreResolver catalogStoreResolver;

	@Autowired
	private PlatformTransactionManager transactionManager;

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
		registry.add("shipping.melhor-envio.client-id", () -> "test-client");
		registry.add("shipping.melhor-envio.client-secret", () -> "test-secret");
		registry.add("shipping.melhor-envio.user-agent", () -> "Commerce (support@example.test)");
		registry.add("shipping.melhor-envio.redirect-uri", () -> "https://panel.example.test/api/public/integrations/melhor-envio/callback");
		registry.add("shipping.melhor-envio.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
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
	void productPhotosShouldUploadReorderAndIsolateStores() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		doNothing().when(photoStorage).put(any(), any(), any());
		doNothing().when(photoStorage).delete(any());
		doAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0))
				.when(photoStorage).url(any());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
		String path = "/api/products/" + fixture.productId() + "/photos";
		String first = mockMvc.perform(multipart(path).file(new MockMultipartFile("file", "x.png", "image/png", output.toByteArray()))
						.header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(multipart(path).file(new MockMultipartFile("file", "x.png", "image/png", output.toByteArray()))
						.header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String a = JsonPath.read(first, "$.id");
		String b = JsonPath.read(second, "$.id");
		mockMvc.perform(put(path + "/order").header("Authorization", "Bearer " + actor.token())
						.contentType(APPLICATION_JSON).content("{\"photoIds\":[\"" + b + "\",\"" + a + "\"]}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(b));
		mockMvc.perform(get("/api/public/catalog/products/{id}", fixture.productId())
						.with(r -> { r.setServerName(storefrontHost(actor)); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.photos[0]").value(org.hamcrest.Matchers.containsString(b)));
		mockMvc.perform(get(path).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
		mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(path + "/" + b).header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isNoContent());
		mockMvc.perform(get(path).header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mockMvc.perform(multipart(path).file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1, 2, 3}))
						.header("Authorization", "Bearer " + actor.token())).andExpect(status().isBadRequest());
	}

	@Test
	void productPhotosShouldEnforceLimitAndCleanFailedOperations() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		doNothing().when(photoStorage).put(any(), any(), any());
		doNothing().when(photoStorage).delete(any());
		doAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0))
				.when(photoStorage).url(any());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
		for (int i = 0; i < 10; i++) {
			photoService.upload(actor.id(), fixture.productId(),
					new MockMultipartFile("file", "x.png", "image/png", output.toByteArray()));
		}
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> photoService.upload(actor.id(),
				fixture.productId(), new MockMultipartFile("file", "x.png", "image/png", output.toByteArray())))
				.getStatusCode().value());
		var first = photoService.list(actor.id(), fixture.productId()).getFirst();
		doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
				.when(photoStorage).delete(any());
		assertEquals(503, assertThrows(ResponseStatusException.class,
				() -> photoService.delete(actor.id(), fixture.productId(), first.id())).getStatusCode().value());
		assertEquals(9, photoService.list(actor.id(), fixture.productId()).size());
		doNothing().when(photoStorage).delete(any());
		photoService.cleanup();
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_product_photos WHERE photo_id = ?",
				Integer.class, first.id()));
		doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
				.when(photoStorage).put(any(), any(), any());
		assertEquals(503, assertThrows(ResponseStatusException.class, () -> photoService.upload(actor.id(),
				fixture.productId(), new MockMultipartFile("file", "x.png", "image/png", output.toByteArray())))
				.getStatusCode().value());
		photoService.cleanup();
		assertEquals(0, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM catalog_product_photos WHERE product_id = ? AND status <> 'READY'
				""", Integer.class, fixture.productId()));
	}

	@Test
	void productPhotosShouldValidateSupportedFormatsAndSize() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		doNothing().when(photoStorage).put(any(), any(), any());
		doAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0))
				.when(photoStorage).url(any());
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
		photoService.upload(actor.id(), fixture.productId(), new MockMultipartFile("file", "x", "text/plain", jpeg.toByteArray()));
		byte[] webp = Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAgA0JaQAA3AA/vv9UAA=");
		photoService.upload(actor.id(), fixture.productId(), new MockMultipartFile("file", "x", "text/plain", webp));
		verify(photoStorage).put(any(), eq("image/jpeg"), any());
		verify(photoStorage).put(any(), eq("image/webp"), any());
		assertEquals(400, assertThrows(ResponseStatusException.class, () -> photoService.upload(actor.id(),
				fixture.productId(), new MockMultipartFile("file", "x.png", "image/png", new byte[10 * 1024 * 1024 + 1])))
				.getStatusCode().value());
	}

	@Test
	void productPhotosShouldEnforceLimitUnderConcurrentUploads() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		doNothing().when(photoStorage).put(any(), any(), any());
		doAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0))
				.when(photoStorage).url(any());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
		for (int i = 0; i < 9; i++) {
			photoService.upload(actor.id(), fixture.productId(),
					new MockMultipartFile("file", "x.png", "image/png", output.toByteArray()));
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> calls = List.of(
					executor.submit(() -> { start.await(); return photoService.upload(actor.id(), fixture.productId(),
							new MockMultipartFile("file", "x.png", "image/png", output.toByteArray())); }),
					executor.submit(() -> { start.await(); return photoService.upload(actor.id(), fixture.productId(),
							new MockMultipartFile("file", "x.png", "image/png", output.toByteArray())); }));
			start.countDown();
			assertOneSuccessOneConflict(calls);
			assertEquals(10, photoService.list(actor.id(), fixture.productId()).size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void shippingEstimatesShouldUseOnlyStoreDataAndRejectInvalidItems() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		UUID otherStore = catalogStoreResolver.storeFor(other.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 5, "Stock", UUID.randomUUID());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(new BigDecimal("0.125"), new BigDecimal("10.25"),
						new BigDecimal("3"), new BigDecimal("20")));
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		var auth = mock(MelhorEnvioOAuthService.class);
		var provider = mock(MelhorEnvioClient.class);
		when(auth.accessToken(store)).thenReturn("store-token");
		var response = new MelhorEnvioClient.QuoteResult(
				List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", new BigDecimal("12.34"), 5)), List.of());
		when(provider.quote(eq("store-token"), any())).thenReturn(response);
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, auth, provider, transactionManager);
		MockMvc api = MockMvcBuilders.standaloneSetup(new ShippingEstimateController(
				new PublicStoreResolver(storeRepository, "localhost"), service)).build();
		String host = storefrontHost(actor);
		String productPath = "/api/public/catalog/products/" + fixture.productId() + "/variants/" + fixture.variantId() + "/shipping-estimates";
		api.perform(post(productPath).contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020360\",\"quantity\":2,\"price\":0}")
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.quotes[0].price").value(12.34))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		ArgumentCaptor<MelhorEnvioClient.QuoteRequest> request = ArgumentCaptor.forClass(MelhorEnvioClient.QuoteRequest.class);
		verify(provider).quote(eq("store-token"), request.capture());
		assertEquals("01001000", request.getValue().originPostalCode());
		assertEquals("96020360", request.getValue().destinationPostalCode());
		assertEquals(2, request.getValue().products().getFirst().quantity());
		assertEquals(0, request.getValue().products().getFirst().unitValue().compareTo(BigDecimal.TEN));
		assertEquals(0, request.getValue().products().getFirst().widthCm().compareTo(new BigDecimal("10.25")));
		assertEquals(fixture.variantId().toString(), request.getValue().products().getFirst().id());
		api.perform(post(productPath).contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020-360\",\"quantity\":2}")
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isBadRequest());
		api.perform(post(productPath).contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020360\",\"quantity\":0}")
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isBadRequest());
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> service.product(otherStore, fixture.productId(), fixture.variantId(), 1, "96020360")).getStatusCode().value());
		verify(auth, never()).accessToken(otherStore);

		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 3);
		var cartBefore = shoppingCartService.get(store, cart);
		api.perform(post("/api/public/cart/shipping-estimates").header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020360\"}")
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.quotes[0].deliveryDays").value(5));
		assertEquals(cartBefore, shoppingCartService.get(store, cart));
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> service.cart(otherStore, cart, "96020360")).getStatusCode().value());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.EXIT, 3, "Adjustment", UUID.randomUUID());
		api.perform(post("/api/public/cart/shipping-estimates").header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020360\"}")
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.items[0].variantId").value(fixture.variantId().toString()))
				.andExpect(jsonPath("$.items[0].reasons[0]").value("UNAVAILABLE"));
	}

	@Test
	void shippingEstimatesShouldNotQuotePartialCartsOrHideProviderFailures() throws Exception {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 2, "Stock", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var auth = mock(MelhorEnvioOAuthService.class);
		var provider = mock(MelhorEnvioClient.class);
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, auth, provider, transactionManager);
		String host = storefrontHost(actor);
		String path = "/api/public/cart/shipping-estimates";
		mockMvc.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON)
						.content("{\"postalCode\":\"96020360\"}").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.items[0].reasons[0]").value("MISSING_MEASUREMENTS"));
		mockMvc.perform(post("/api/public/catalog/products/{productId}/variants/{variantId}/shipping-estimates",
						fixture.productId(), fixture.variantId()).contentType(APPLICATION_JSON)
						.content("{\"postalCode\":\"96020360\",\"quantity\":1}").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ITEMS_NOT_QUOTABLE"));
		mockMvc.perform(post(path).contentType(APPLICATION_JSON).content("{\"postalCode\":\"96020360\"}")
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isNotFound());
		var issue = assertThrows(ShippingEstimateService.InvalidItems.class, () -> service.cart(store, token, "96020360"));
		assertEquals(List.of("MISSING_MEASUREMENTS"), issue.items().getFirst().reasons());
		verify(auth, never()).accessToken(any());
		verify(provider, never()).quote(any(), any());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(new BigDecimal("0.125"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.cart(store, token, "96020360")).getStatusCode().value());
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		when(auth.accessToken(store)).thenReturn("store-token");
		when(provider.quote(eq("store-token"), any())).thenThrow(new MelhorEnvioException(MelhorEnvioException.Reason.RATE_LIMIT));
		MockMvc api = MockMvcBuilders.standaloneSetup(new ShippingEstimateController(
				new PublicStoreResolver(storeRepository, "localhost"), service)).build();
		api.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON)
						.content("{\"postalCode\":\"96020360\"}").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SHIPPING_TEMPORARILY_UNAVAILABLE"))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		String empty = shoppingCartService.create(store).token();
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.cart(store, empty, "96020360")).getStatusCode().value());
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 2);
		checkoutReservationService.start(store, token);
		assertEquals("UNAVAILABLE", assertThrows(ShippingEstimateService.InvalidItems.class,
				() -> service.cart(store, token, "96020360")).items().getFirst().reasons().getFirst());
	}

	@Test
	void checkoutShippingEstimatesShouldQuoteOnlyReservedItemsUsingSnapshotPrices() throws Exception {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		InventoryFixture fixture = inventoryFixture(actor);
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Second type", false, false, null));
		Product second = productService.create(actor.id(), new ProductRequest(type.getId(), "SKU2", "Second",
				"Description", BigDecimal.TEN, List.of(new VariantRequest(null, null, null))));
		UUID secondVariant = jdbcTemplate.queryForObject(
				"SELECT variant_id FROM catalog_product_variants WHERE product_id = ?", UUID.class, second.getId());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 2, "Stock", UUID.randomUUID());
		inventoryService.move(actor.id(), second.getId(), secondVariant, MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(new BigDecimal("0.125"), new BigDecimal("10.25"), BigDecimal.TEN, BigDecimal.TEN));
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 2);
		shoppingCartService.setQuantity(store, token, second.getId(), secondVariant, 1);
		inventoryService.move(actor.id(), second.getId(), secondVariant, MovementType.EXIT, 1, "Adjustment", UUID.randomUUID());
		var reservation = checkoutReservationService.start(store, token);
		assertEquals(1, reservation.items().stream().filter(CheckoutReservationService.Item::reserved).count());
		jdbcTemplate.update("UPDATE catalog_products SET price = 99 WHERE store_id = ? AND product_id = ?", store, fixture.productId());
		var auth = mock(MelhorEnvioOAuthService.class);
		var provider = mock(MelhorEnvioClient.class);
		when(auth.accessToken(store)).thenReturn("store-token");
		when(provider.quote(eq("store-token"), any())).thenReturn(new MelhorEnvioClient.QuoteResult(
				List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", new BigDecimal("12.34"), 5)), List.of()));
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, auth, provider, transactionManager);
		MockMvc api = MockMvcBuilders.standaloneSetup(new ShippingEstimateController(
				new PublicStoreResolver(storeRepository, "localhost"), service)).build();
		String path = "/api/public/checkout/" + reservation.id() + "/shipping-estimates";
		api.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON)
						.content("{\"postalCode\":\"96020360\"}").with(r -> { r.setServerName(storefrontHost(actor)); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.quoteId").exists())
				.andExpect(jsonPath("$.request").doesNotExist())
				.andExpect(jsonPath("$.reservationId").value(reservation.id().toString()))
				.andExpect(jsonPath("$.expiresAt").exists()).andExpect(jsonPath("$.quotes[0].price").value(12.34))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		ArgumentCaptor<MelhorEnvioClient.QuoteRequest> request = ArgumentCaptor.forClass(MelhorEnvioClient.QuoteRequest.class);
		verify(provider).quote(eq("store-token"), request.capture());
		assertEquals("01001000", request.getValue().originPostalCode());
		assertEquals("96020360", request.getValue().destinationPostalCode());
		assertEquals(1, request.getValue().products().size());
		var product = request.getValue().products().getFirst();
		assertEquals(fixture.variantId().toString(), product.id());
		assertEquals(2, product.quantity());
		assertEquals(0, product.unitValue().compareTo(BigDecimal.TEN));
		assertEquals(0, product.widthCm().compareTo(new BigDecimal("10.25")));
		assertEquals(reservation, checkoutReservationService.get(store, token));
	}

	@Test
	void checkoutShippingEstimatesShouldValidateAccessMeasurementsAndProviderErrors() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		UUID otherStore = catalogStoreResolver.storeFor(other.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var reservation = checkoutReservationService.start(store, token);
		var auth = mock(MelhorEnvioOAuthService.class);
		var provider = mock(MelhorEnvioClient.class);
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, auth, provider, transactionManager);
		String path = "/api/public/checkout/" + reservation.id() + "/shipping-estimates";
		String host = storefrontHost(actor);
		String body = "{\"postalCode\":\"96020360\"}";
		mockMvc.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON).content(body)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ITEMS_NOT_QUOTABLE"))
				.andExpect(jsonPath("$.items[0].reasons[0]").value("MISSING_MEASUREMENTS"));
		mockMvc.perform(post(path).contentType(APPLICATION_JSON).content(body)
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isNotFound());
		mockMvc.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON)
						.content("{\"postalCode\":\"96020-360\"}").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isBadRequest());
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> service.checkout(otherStore, token, reservation.id(), "96020360")).getStatusCode().value());
		String otherCart = shoppingCartService.create(store).token();
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, otherCart, reservation.id(), "96020360")).getStatusCode().value());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, UUID.randomUUID(), "96020360")).getStatusCode().value());
		verify(auth, never()).accessToken(any());
		verify(provider, never()).quote(any(), any());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, reservation.id(), "96020360")).getStatusCode().value());
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		when(auth.accessToken(store)).thenReturn("store-token");
		when(provider.quote(eq("store-token"), any())).thenThrow(new MelhorEnvioException(MelhorEnvioException.Reason.UNAVAILABLE));
		MockMvc api = MockMvcBuilders.standaloneSetup(new ShippingEstimateController(
				new PublicStoreResolver(storeRepository, "localhost"), service)).build();
		api.perform(post(path).header("Cart-Token", token).contentType(APPLICATION_JSON).content(body)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SHIPPING_TEMPORARILY_UNAVAILABLE"));
		checkoutReservationService.cancel(store, token, reservation.id());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, reservation.id(), "96020360")).getStatusCode().value());
	}

	@Test
	void checkoutShippingEstimatesShouldRejectReservationCancelledOrExpiredDuringProviderCall() {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var auth = mock(MelhorEnvioOAuthService.class);
		var provider = mock(MelhorEnvioClient.class);
		when(auth.accessToken(store)).thenReturn("store-token");
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, auth, provider, transactionManager);
		var first = checkoutReservationService.start(store, token);
		when(provider.quote(eq("store-token"), any())).thenAnswer(invocation -> {
			checkoutReservationService.cancel(store, token, first.id());
			return new MelhorEnvioClient.QuoteResult(List.of(), List.of());
		});
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, first.id(), "96020360")).getStatusCode().value());
		var second = checkoutReservationService.start(store, token);
		doAnswer(invocation -> {
			jdbcTemplate.update("""
					UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
					    expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
					""", second.id());
			return new MelhorEnvioClient.QuoteResult(List.of(), List.of());
		}).when(provider).quote(eq("store-token"), any());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, second.id(), "96020360")).getStatusCode().value());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> service.checkout(store, token, second.id(), "96020360")).getStatusCode().value());
	}

	@Test
	void shippingEstimatesShouldReportEveryUnquotableCartItemAndRefreshStoreAuthorization() {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		InventoryFixture fixture = inventoryFixture(actor);
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Second type", false, false, null));
		Product second = productService.create(actor.id(), new ProductRequest(type.getId(), "SKU2", "Second",
				"Description", BigDecimal.TEN, List.of(new VariantRequest(null, null, null))));
		UUID secondVariant = jdbcTemplate.queryForObject(
				"SELECT variant_id FROM catalog_product_variants WHERE product_id = ?", UUID.class, second.getId());
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 2, "Stock", UUID.randomUUID());
		inventoryService.move(actor.id(), second.getId(), secondVariant, MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
				new VariantShippingRequest(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 1);
		shoppingCartService.setQuantity(store, cart, second.getId(), secondVariant, 1);
		productService.setVariantActive(actor.id(), second.getId(), secondVariant, false);
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		var oauthClient = mock(MelhorEnvioOAuthClient.class);
		var tokens = mock(MelhorEnvioOAuthClient.Tokens.class);
		when(tokens.accessToken()).thenReturn("old-token", "fresh-token");
		when(tokens.refreshToken()).thenReturn("refresh-token", "next-refresh");
		when(tokens.expiresIn()).thenReturn(1L, 3600L);
		when(oauthClient.exchange("code")).thenReturn(tokens);
		when(oauthClient.refresh("refresh-token")).thenReturn(tokens);
		var authorization = new MelhorEnvioOAuthService(jdbcTemplate, transactionManager, catalogStoreResolver,
				oauthClient, "client", "https://panel.example.test/api/public/integrations/melhor-envio/callback",
				Base64.getEncoder().encodeToString(new byte[32]));
		var start = authorization.start(actor.id(), "panel.example.test");
		String state = UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams().getFirst("state");
		authorization.complete(state, start.browserSecret(), "code", null);
		var provider = mock(MelhorEnvioClient.class);
		var result = new MelhorEnvioClient.QuoteResult(List.of(), List.of(new MelhorEnvioClient.UnavailableService(1, "PAC")));
		when(provider.quote(eq("fresh-token"), any())).thenReturn(result);
		var service = new ShippingEstimateService(jdbcTemplate, shoppingCartService, authorization, provider, transactionManager);
		var issue = assertThrows(ShippingEstimateService.InvalidItems.class, () -> service.cart(store, cart, "96020360"));
		assertEquals(1, issue.items().size());
		assertEquals(secondVariant, issue.items().getFirst().variantId());
		assertEquals(List.of("UNAVAILABLE", "MISSING_MEASUREMENTS"), issue.items().getFirst().reasons());
		verify(provider, never()).quote(any(), any());
		verify(oauthClient, never()).refresh(any());
		productService.setVariantActive(actor.id(), second.getId(), secondVariant, true);
		productService.updateVariantShipping(actor.id(), second.getId(), secondVariant,
				new VariantShippingRequest(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
		assertEquals(result, service.cart(store, cart, "96020360"));
		verify(oauthClient).refresh("refresh-token");
		ArgumentCaptor<MelhorEnvioClient.QuoteRequest> request = ArgumentCaptor.forClass(MelhorEnvioClient.QuoteRequest.class);
		verify(provider).quote(eq("fresh-token"), request.capture());
		assertEquals(2, request.getValue().products().size());
	}

	@Test
	void melhorEnvioOAuthShouldBindBrowserStoreAndRotateEncryptedTokens() {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		MelhorEnvioOAuthClient provider = mock(MelhorEnvioOAuthClient.class);
		var tokens = mock(MelhorEnvioOAuthClient.Tokens.class);
		when(tokens.accessToken()).thenReturn("access-one", "access-two");
		when(tokens.refreshToken()).thenReturn("refresh-one", "refresh-two");
		when(tokens.expiresIn()).thenReturn(1L, 3600L);
		when(provider.exchange("code")).thenReturn(tokens);
		when(provider.refresh("refresh-one")).thenReturn(tokens);
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		var service = new MelhorEnvioOAuthService(jdbcTemplate, transactionManager,
				catalogStoreResolver, provider, "client",
				"https://panel.example.test/api/public/integrations/melhor-envio/callback", key);
		var start = service.start(actor.id(), "panel.example.test");
		String state = UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams().getFirst("state");
		assertNotNull(state);
		assertEquals("PENDING", service.status(actor.id()));
		assertEquals("DISCONNECTED", service.status(other.id()));
		assertThrows(IllegalArgumentException.class, () -> service.complete(state, "wrong-browser", "code", null));
		service.complete(state, start.browserSecret(), "code", null);
		assertEquals("CONNECTED", service.status(actor.id()));
		assertThrows(IllegalArgumentException.class, () -> service.complete(state, start.browserSecret(), "code", null));
		byte[] stored = jdbcTemplate.queryForObject("SELECT access_token FROM melhor_envio_connections WHERE store_id = ?", byte[].class, store);
		assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("access-one"));
		assertEquals("access-two", service.accessToken(store));
		verify(provider).refresh("refresh-one");
		assertEquals("access-two", service.accessToken(store));
		service.disconnect(actor.id());
		assertEquals("DISCONNECTED", service.status(actor.id()));
		assertThrows(MelhorEnvioException.class, () -> service.accessToken(store));
	}

	@Test
	void melhorEnvioOAuthEndpointsShouldRequireMerchantAndProtectCallback() throws Exception {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		String customer = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		String connect = "/api/integrations/melhor-envio/connect";
		mockMvc.perform(post(connect)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(connect).header("Authorization", "Bearer " + customer)).andExpect(status().isForbidden());
		mockMvc.perform(post(connect).header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(connect).header("Authorization", "Bearer " + actor.token())
						.with(r -> { r.setServerName("panel.example.test"); r.setSecure(true); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.authorizationUrl").exists())
				.andExpect(r -> assertTrue(r.getResponse().getHeader("Set-Cookie").contains("__Host-me-oauth=")))
				.andExpect(r -> assertTrue(r.getResponse().getHeader("Set-Cookie").contains("Secure")))
				.andExpect(r -> assertTrue(r.getResponse().getHeader("Set-Cookie").contains("HttpOnly")))
				.andExpect(r -> assertTrue(r.getResponse().getHeader("Set-Cookie").contains("SameSite=Lax")))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		mockMvc.perform(get("/api/integrations/melhor-envio").header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
		mockMvc.perform(get("/api/public/integrations/melhor-envio/callback").param("state", "invalid").param("code", "code"))
				.andExpect(status().isBadRequest())
				.andExpect(r -> assertEquals("no-referrer", r.getResponse().getHeader("Referrer-Policy")))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		mockMvc.perform(delete("/api/integrations/melhor-envio").header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isNoContent());
	}

	@Test
	void melhorEnvioRefreshShouldRejectConcurrentRefreshAndDisconnect() throws Exception {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		MelhorEnvioOAuthClient provider = mock(MelhorEnvioOAuthClient.class);
		var tokens = mock(MelhorEnvioOAuthClient.Tokens.class);
		when(tokens.accessToken()).thenReturn("access");
		when(tokens.refreshToken()).thenReturn("refresh");
		when(tokens.expiresIn()).thenReturn(1L);
		when(provider.exchange("code")).thenReturn(tokens);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		when(provider.refresh("refresh")).thenAnswer(invocation -> {
			entered.countDown();
			if (!finish.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Refresh did not resume.");
			}

			return tokens;
		});
		var service = new MelhorEnvioOAuthService(jdbcTemplate, transactionManager, catalogStoreResolver,
				provider, "client", "https://panel.example.test/api/public/integrations/melhor-envio/callback",
				Base64.getEncoder().encodeToString(new byte[32]));
		var start = service.start(actor.id(), "panel.example.test");
		String state = UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams().getFirst("state");
		service.complete(state, start.browserSecret(), "code", null);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> refresh = executor.submit(() -> service.accessToken(store));
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			assertEquals(MelhorEnvioException.Reason.UNAVAILABLE,
					assertThrows(MelhorEnvioException.class, () -> service.accessToken(store)).reason());
			service.disconnect(actor.id());
			finish.countDown();
			assertInstanceOf(MelhorEnvioException.class, assertThrows(ExecutionException.class,
					() -> refresh.get(10, TimeUnit.SECONDS)).getCause());
			assertEquals("DISCONNECTED", service.status(actor.id()));
		} finally {
			finish.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void melhorEnvioOAuthShouldRejectExpiredAndSupersededStates() {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		MelhorEnvioOAuthClient provider = mock(MelhorEnvioOAuthClient.class);
		var service = new MelhorEnvioOAuthService(jdbcTemplate, transactionManager, catalogStoreResolver,
				provider, "client", "https://panel.example.test/api/public/integrations/melhor-envio/callback",
				Base64.getEncoder().encodeToString(new byte[32]));
		var old = service.start(actor.id(), "panel.example.test");
		String oldState = UriComponentsBuilder.fromUriString(old.authorizationUrl()).build().getQueryParams().getFirst("state");
		var current = service.start(actor.id(), "panel.example.test");
		String currentState = UriComponentsBuilder.fromUriString(current.authorizationUrl()).build().getQueryParams().getFirst("state");
		assertThrows(IllegalArgumentException.class, () -> service.complete(oldState, old.browserSecret(), "code", null));
		jdbcTemplate.update("UPDATE melhor_envio_connections SET state_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE store_id = ?", store);
		assertEquals("REAUTHORIZE", service.status(actor.id()));
		assertThrows(IllegalArgumentException.class, () -> service.complete(currentState, current.browserSecret(), "code", null));
		assertThrows(MelhorEnvioException.class, () -> service.start(actor.id(), "other.example.test"));
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

	private ShippingEstimateService.CheckoutQuote shippingFixture(CatalogActor actor, UUID store, String token,
	                                                               CheckoutReservationService.Checkout reservation) {
		for (var item : reservation.items()) {
			if (item.reserved()) {
				productService.updateVariantShipping(actor.id(), item.productId(), item.variantId(),
						new VariantShippingRequest(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
			}
		}
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", true, null));
		doReturn("store-token").when(shippingAuthorization).accessToken(store);
		doReturn(new MelhorEnvioClient.QuoteResult(
				List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", BigDecimal.ZERO, 5)), List.of()))
				.when(shippingProvider).quote(eq("store-token"), any());
		return shippingEstimateService.checkout(store, token, reservation.id(), "01001000");
	}

	private OrderService.Order deliveryOrder(UUID store, String token, UUID reservation,
	                                         CustomerAuthService.Customer customer, boolean partial, UUID address,
	                                         UUID quoteId) {
		return orderCheckoutService.create(store, token, reservation, customer, partial, address,
				OrderService.FulfillmentType.DELIVERY, quoteId, 1);
	}

	@Test
	void deliveryOrderShouldRequireReconfirmationAndSnapshotServerShipping() throws Exception {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID address = deliveryFixture(store, customer.id());
		String customerToken = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 1);
		var reservation = checkoutReservationService.start(store, cart);
		var old = shippingFixture(actor, store, cart, reservation);
		String host = storefrontHost(actor);
		String path = "/api/public/checkout/" + reservation.id() + "/orders";
		String request = "{\"acceptPartial\":false,\"addressId\":\"" + address
				+ "\",\"quoteId\":\"" + old.quoteId() + "\",\"shippingServiceId\":1,\"price\":0}";
		String missingSelection = "{\"acceptPartial\":false,\"addressId\":\"" + address + "\"}";
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(missingSelection).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict());
		doReturn(new MelhorEnvioClient.QuoteResult(
				List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", new BigDecimal("12.34"), 7)), List.of()))
				.when(shippingProvider).quote(eq("store-token"), any());
		String changed = mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(request).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIPPING_QUOTE_CHANGED"))
				.andExpect(jsonPath("$.quote.quotes[0].price").value(12.34))
				.andExpect(jsonPath("$.quote.quotes[0].deliveryDays").value(7))
				.andExpect(jsonPath("$.quote.request").doesNotExist())
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")))
				.andReturn().getResponse().getContentAsString();
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
				Integer.class, reservation.id()));
		String newQuote = JsonPath.read(changed, "$.quote.quoteId");
		String confirmed = request.replace(old.quoteId().toString(), newQuote);
		String order = mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(confirmed).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.delivery.shipping.serviceId").value(1))
				.andExpect(jsonPath("$.delivery.shipping.price").value(12.34))
				.andExpect(jsonPath("$.delivery.shipping.deliveryDays").value(7))
				.andExpect(jsonPath("$.total").value(22.34)).andReturn().getResponse().getContentAsString();
		String orderId = JsonPath.read(order, "$.id");
		doReturn(new MelhorEnvioClient.QuoteResult(List.of(), List.of())).when(shippingProvider).quote(eq("store-token"), any());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(confirmed).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(orderId)).andExpect(jsonPath("$.total").value(22.34));
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(request).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/public/customer/orders/{id}", orderId).header("Authorization", "Bearer " + customerToken)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.delivery.shipping.price").value(12.34));
		mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + actor.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].total").value(22.34));
		mockMvc.perform(get("/api/public/customer/orders").header("Authorization", "Bearer " + customerToken)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].total").value(22.34));
		jdbcTemplate.update("DELETE FROM order_shipping_details WHERE order_id = ?", UUID.fromString(orderId));
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(missingSelection).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(orderId))
				.andExpect(jsonPath("$.delivery.shipping").doesNotExist()).andExpect(jsonPath("$.total").value(10));
	}

	@Test
	void deliveryOrderShouldRejectChangedInputsAndForeignQuotes() throws Exception {
		CatalogActor actor = catalogActor();
		CatalogActor other = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID address = deliveryFixture(store, customer.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 1);
		var reservation = checkoutReservationService.start(store, cart);
		var quote = shippingFixture(actor, store, cart, reservation);
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, UUID.randomUUID())).getStatusCode().value());
		assertEquals(404, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				catalogStoreResolver.storeFor(other.id()), cart, reservation.id(), customer, false, address, quote.quoteId()))
				.getStatusCode().value());
		String anotherCart = shoppingCartService.create(store).token();
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, anotherCart, reservation.id(), customer, false, address, quote.quoteId())).getStatusCode().value());
		doAnswer(invocation -> {
			productService.updateVariantShipping(actor.id(), fixture.productId(), fixture.variantId(),
					new VariantShippingRequest(new BigDecimal("2"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
			return new MelhorEnvioClient.QuoteResult(
					List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", BigDecimal.ZERO, 5)), List.of());
		}).when(shippingProvider).quote(eq("store-token"), any());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, quote.quoteId())).getStatusCode().value());
		doAnswer(invocation -> {
			customerProfileService.update(store, customer.id(), address, new CustomerProfileService.AddressRequest(
					"Ana", "96020360", "Rua B", "10", null, "Centro", "Sao Paulo", "SP"));
			return new MelhorEnvioClient.QuoteResult(
					List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", BigDecimal.ZERO, 5)), List.of());
		}).when(shippingProvider).quote(eq("store-token"), any());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, quote.quoteId())).getStatusCode().value());
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
				Integer.class, reservation.id()));
	}

	@Test
	void deliveryOrderShouldReturnUnavailableRequoteWithoutCreatingOrder() {
		CatalogActor actor = catalogActor();
		UUID store = catalogStoreResolver.storeFor(actor.id());
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		UUID address = deliveryFixture(store, customer.id());
		InventoryFixture fixture = inventoryFixture(actor);
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Stock", UUID.randomUUID());
		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 1);
		var reservation = checkoutReservationService.start(store, cart);
		var quote = shippingFixture(actor, store, cart, reservation);
		doReturn(new MelhorEnvioClient.QuoteResult(List.of(), List.of(new MelhorEnvioClient.UnavailableService(1, "PAC"))))
				.when(shippingProvider).quote(eq("store-token"), any());
		var changed = assertThrows(OrderCheckoutService.ShippingChanged.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, quote.quoteId()));
		assertEquals(1, changed.quote().unavailable().getFirst().id());
		doReturn(new MelhorEnvioClient.QuoteResult(
				List.of(new MelhorEnvioClient.ServiceQuote(1, "PAC", BigDecimal.ZERO, 6)), List.of()))
				.when(shippingProvider).quote(eq("store-token"), any());
		assertEquals(6, assertThrows(OrderCheckoutService.ShippingChanged.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, quote.quoteId()))
				.quote().quotes().getFirst().deliveryDays());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderCheckoutService.create(
				store, cart, reservation.id(), customer, false, address, OrderService.FulfillmentType.DELIVERY,
				quote.quoteId(), 999)).getStatusCode().value());
		doThrow(new MelhorEnvioException(MelhorEnvioException.Reason.UNAVAILABLE))
				.when(shippingProvider).quote(eq("store-token"), any());
		assertThrows(MelhorEnvioException.class, () -> deliveryOrder(
				store, cart, reservation.id(), customer, false, address, quote.quoteId()));
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
				Integer.class, reservation.id()));
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
		var shippingQuote = shippingFixture(actor, store, token, checkoutReservationService.get(store, token));
		String orderPath = "/api/public/checkout/" + reservation + "/orders";
		String body = "{\"acceptPartial\":true,\"addressId\":\"" + addressId
				+ "\",\"quoteId\":\"" + shippingQuote.quoteId() + "\",\"shippingServiceId\":1}";
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
						.content(body.replace("\"acceptPartial\":true", "\"acceptPartial\":false"))
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
				.andExpect(jsonPath("$.delivery").doesNotExist()).andExpect(jsonPath("$.fulfillmentType").value("DELIVERY"));
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + customerToken)
						.with(request -> { request.setServerName(host); return request; }))
				.andExpect(status().isNotFound());
		assertEquals(2, inventoryService.balance(actor.id(), product.getId(), black).quantity());
	}

	@Test
	void publicPickupOptionShouldExposeOnlyEnabledStoreSettings() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(owner);
		String settings = """
				{"postalCode":"01001000","street":"Rua A","number":"10","complement":null,
				 "district":"Centro","city":"Sao Paulo","state":"SP","pickupEnabled":true,
				 "pickupInstructions":"Bring ID"}
				""";
		mockMvc.perform(get("/api/public/pickup").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.address").doesNotExist());
		mockMvc.perform(put("/api/store/shipping-settings").header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(settings)).andExpect(status().isOk());
		mockMvc.perform(get("/api/public/pickup").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.address.street").value("Rua A"))
				.andExpect(jsonPath("$.address.country").value("BR"))
				.andExpect(jsonPath("$.instructions").value("Bring ID"))
				.andExpect(r -> assertEquals("no-store", r.getResponse().getHeader("Cache-Control")));
		mockMvc.perform(get("/api/public/pickup").with(r -> { r.setServerName(storefrontHost(other)); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.address").doesNotExist());
		mockMvc.perform(put("/api/store/shipping-settings").header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(settings.replace("true", "false"))).andExpect(status().isOk());
		mockMvc.perform(get("/api/public/pickup").with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.address").doesNotExist()).andExpect(jsonPath("$.instructions").doesNotExist());
		mockMvc.perform(get("/api/public/pickup").with(r -> { r.setServerName("unknown-" + UUID.randomUUID() + ".localhost"); return r; }))
				.andExpect(status().isNotFound());
	}

	@Test
	void pickupOrderShouldSnapshotSettingsAndEnforceFulfillmentRules() throws Exception {
		CatalogActor owner = catalogActor();
		CatalogActor other = catalogActor();
		String host = storefrontHost(owner);
		UUID merchantId = merchantAccountRepository.findById(owner.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		customerAuthService.register(store, "Other", "other@example.com", "a-strong-password");
		String customerToken = customerAuthService.login(store, "ana@example.com", "a-strong-password").accessToken();
		String otherToken = customerAuthService.login(store, "other@example.com", "a-strong-password").accessToken();
		InventoryFixture fixture = inventoryFixture(owner);
		inventoryService.move(owner.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String cart = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, cart, fixture.productId(), fixture.variantId(), 1);
		var checkout = checkoutReservationService.start(store, cart);
		String path = "/api/public/checkout/" + checkout.id() + "/orders";
		String body = "{\"acceptPartial\":false,\"fulfillmentType\":\"PICKUP\"}";
		String settings = """
				{"postalCode":"01001000","street":"Rua A","number":"10","complement":null,
				 "district":"Centro","city":"Sao Paulo","state":"SP","pickupEnabled":true,
				 "pickupInstructions":"Bring ID"}
				""";
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict());
		mockMvc.perform(put("/api/store/shipping-settings").header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(settings)).andExpect(status().isOk());
		for (String invalid : List.of("{\"acceptPartial\":false,\"fulfillmentType\":\"PICKUP\",\"addressId\":\"" + UUID.randomUUID() + "\"}",
				"{\"acceptPartial\":false,\"fulfillmentType\":\"DELIVERY\"}",
				"{\"acceptPartial\":false,\"fulfillmentType\":\"UNKNOWN\"}")) {
			mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
							.contentType(APPLICATION_JSON).content(invalid).with(r -> { r.setServerName(host); return r; }))
					.andExpect(status().isBadRequest());
		}
		jdbcTemplate.update("UPDATE customer_accounts SET phone = NULL WHERE customer_id = ?", customer.id());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict());
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		String order = mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.fulfillmentType").value("PICKUP"))
				.andExpect(jsonPath("$.pickup.cost").value(0)).andExpect(jsonPath("$.total").value(10))
				.andExpect(jsonPath("$.delivery").doesNotExist()).andExpect(jsonPath("$.pickup.phone").value("11987654321"))
				.andExpect(jsonPath("$.pickup.address.street").value("Rua A"))
				.andExpect(jsonPath("$.pickup.instructions").value("Bring ID"))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(order, "$.id");
		mockMvc.perform(put("/api/store/shipping-settings").header("Authorization", "Bearer " + owner.token())
						.contentType(APPLICATION_JSON).content(settings.replace("Rua A", "Rua B")
								.replace("Bring ID", "New instructions").replace("true", "false")))
				.andExpect(status().isOk());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.pickup.address.street").value("Rua A"))
				.andExpect(jsonPath("$.pickup.instructions").value("Bring ID"));
		mockMvc.perform(post(path).header("Authorization", "Bearer " + otherToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isConflict());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content("{\"acceptPartial\":true,\"fulfillmentType\":\"PICKUP\"}")
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isConflict());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content("{\"acceptPartial\":false,\"addressId\":\"" + UUID.randomUUID() + "\"}")
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isConflict());
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + otherToken)
						.with(r -> { r.setServerName(host); return r; })).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/public/customer/orders/{id}", id).header("Authorization", "Bearer " + customerToken)
						.with(r -> { r.setServerName(storefrontHost(other)); return r; })).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/orders/{id}", id).header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/orders/{id}", id).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.pickup.address.street").value("Rua A"))
				.andExpect(jsonPath("$.pickup.instructions").value("Bring ID"));
		mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].fulfillmentType").value("PICKUP"));
		mockMvc.perform(get("/api/public/customer/orders").header("Authorization", "Bearer " + customerToken)
						.with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].fulfillmentType").value("PICKUP"));
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", checkout.id());
		mockMvc.perform(post(path).header("Authorization", "Bearer " + customerToken).header("Cart-Token", cart)
						.contentType(APPLICATION_JSON).content(body).with(r -> { r.setServerName(host); return r; }))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.status").value("EXPIRED"));
	}

	@Test
	void pickupOrderShouldSerializeWithSettingsAndConcurrentRetries() throws Exception {
		CatalogActor actor = catalogActor();
		InventoryFixture fixture = inventoryFixture(actor);
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", true, "Old"));
		inventoryService.move(actor.id(), fixture.productId(), fixture.variantId(), MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		String token = shoppingCartService.create(store).token();
		shoppingCartService.setQuantity(store, token, fixture.productId(), fixture.variantId(), 1);
		var checkout = checkoutReservationService.start(store, token);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement(
					"UPDATE store_shipping_settings SET street = ?, pickup_instructions = ? WHERE store_id = ?")) {
				statement.setString(1, "Rua B");
				statement.setString(2, "New");
				statement.setObject(3, store);
				statement.executeUpdate();
			}
			CountDownLatch started = new CountDownLatch(1);
			Future<OrderService.Order> first = executor.submit(() -> {
				started.countDown();
				return orderService.create(store, token, checkout.id(), customer, false, null, OrderService.FulfillmentType.PICKUP);
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> first.get(300, TimeUnit.MILLISECONDS));
			Future<OrderService.Order> retry = executor.submit(() -> orderService.create(
					store, token, checkout.id(), customer, false, null, OrderService.FulfillmentType.PICKUP));
			connection.commit();
			var order = first.get(30, TimeUnit.SECONDS);
			assertEquals("Rua B", order.pickup().address().street());
			assertEquals("New", order.pickup().instructions());
			assertEquals(order.id(), retry.get(30, TimeUnit.SECONDS).id());
			assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
					Integer.class, checkout.id()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void pickupOrderShouldRespectPartialCheckoutAndCancellation() {
		CatalogActor actor = catalogActor();
		UUID merchantId = merchantAccountRepository.findById(actor.id()).orElseThrow().getMerchantId();
		UUID store = storeRepository.findByMerchantId(merchantId).orElseThrow().getId();
		var customer = customerAuthService.register(store, "Ana", "ana@example.com", "a-strong-password");
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", true, null));
		ItemType type = itemTypeService.create(actor.id(), new ItemTypeValues("Shirts", true, false, null));
		Product product = productService.create(actor.id(), new ProductRequest(type.getId(), "SKU", "Shirt",
				"Cotton", BigDecimal.TEN, List.of(new VariantRequest("Black", null, null), new VariantRequest("White", null, null))));
		List<UUID> variants = jdbcTemplate.query("SELECT variant_id FROM catalog_product_variants WHERE product_id = ? ORDER BY color",
				(rs, row) -> rs.getObject(1, UUID.class), product.getId());
		for (UUID variant : variants) {
			inventoryService.move(actor.id(), product.getId(), variant, MovementType.ENTRY, 1, "Delivery", UUID.randomUUID());
		}
		String token = shoppingCartService.create(store).token();
		for (UUID variant : variants) {
			shoppingCartService.setQuantity(store, token, product.getId(), variant, 1);
		}
		inventoryService.move(actor.id(), product.getId(), variants.getLast(), MovementType.EXIT, 1, "Adjustment", UUID.randomUUID());
		var checkout = checkoutReservationService.start(store, token);
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.create(
				store, token, checkout.id(), customer, false, null, OrderService.FulfillmentType.PICKUP)).getStatusCode().value());
		var order = orderService.create(store, token, checkout.id(), customer, true, null, OrderService.FulfillmentType.PICKUP);
		assertEquals(1, order.items().size());
		assertEquals(variants.getFirst(), order.items().getFirst().variantId());
		checkoutReservationService.cancel(store, token, checkout.id());
		assertEquals("CANCELLED", orderService.create(store, token, checkout.id(), customer, true,
				null, OrderService.FulfillmentType.PICKUP).status());
		var next = checkoutReservationService.start(store, token);
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", false, null));
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.create(
				store, token, next.id(), customer, true, null, OrderService.FulfillmentType.PICKUP)).getStatusCode().value());
		storeShippingSettingsService.update(actor.id(), new StoreShippingSettingsService.Settings(
				"01001000", "Rua A", "10", null, "Centro", "Sao Paulo", "SP", true, null));
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", next.id());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.create(
				store, token, next.id(), customer, true, null, OrderService.FulfillmentType.PICKUP)).getStatusCode().value());
		assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commerce_orders WHERE reservation_id = ?",
				Integer.class, next.id()));
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
		var shippingQuote = shippingFixture(actor, store, token, first);
		assertEquals(404, assertThrows(ResponseStatusException.class,
				() -> deliveryOrder(store, token, first.id(), customer, false, foreignAddress, shippingQuote.quoteId())).getStatusCode().value());
		jdbcTemplate.update("UPDATE customer_accounts SET phone = NULL WHERE customer_id = ?", customer.id());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> deliveryOrder(store, token, first.id(), customer, false, addressId, shippingQuote.quoteId())).getStatusCode().value());
		customerProfileService.updateProfile(store, customer.id(), new CustomerProfileService.ProfileRequest("Ana", "11987654321"));
		var order = deliveryOrder(store, token, first.id(), customer, false, addressId, shippingQuote.quoteId());
		customerProfileService.update(store, customer.id(), addressId, new CustomerProfileService.AddressRequest(
				"Ana", "01001000", "Rua Nova", "10", null, "Centro", "Sao Paulo", "SP"));
		customerProfileService.delete(store, customer.id(), addressId);
		assertEquals("Rua A", orderService.ownedOrder(store, customer.id(), order.id()).delivery().address().street());
		assertEquals("Rua A", deliveryOrder(store, token, first.id(), customer, false, addressId, shippingQuote.quoteId()).delivery().address().street());
		assertEquals(409, assertThrows(ResponseStatusException.class,
				() -> deliveryOrder(store, token, first.id(), customer, false, foreignAddress, shippingQuote.quoteId())).getStatusCode().value());
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", first.id());
		assertEquals("EXPIRED", orderService.ownedOrder(store, customer.id(), order.id()).status());
		assertEquals("EXPIRED", deliveryOrder(store, token, first.id(), customer, false, addressId, shippingQuote.quoteId()).status());
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?",
				TokenService.hashToken(token));
		assertEquals("EXPIRED", orderService.ownedOrder(store, customer.id(), order.id()).status());
		jdbcTemplate.update("UPDATE shopping_carts SET expires_at = clock_timestamp() + INTERVAL '1 day' WHERE token_hash = ?",
				TokenService.hashToken(token));
		var second = checkoutReservationService.start(store, token);
		assertNotEquals(first.id(), second.id());
		var secondQuote = shippingFixture(actor, store, token, second);
		checkoutReservationService.cancel(store, token, second.id());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, token, second.id(), customer, false, addressId, secondQuote.quoteId())).getStatusCode().value());
		var third = checkoutReservationService.start(store, token);
		var thirdQuote = shippingFixture(actor, store, token, third);
		jdbcTemplate.update("""
				UPDATE checkout_reservations SET created_at = clock_timestamp() - INTERVAL '21 minutes',
				expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE reservation_id = ?
				""", third.id());
		assertEquals(409, assertThrows(ResponseStatusException.class, () -> deliveryOrder(
				store, token, third.id(), customer, false, addressId, thirdQuote.quoteId())).getStatusCode().value());
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
		var shippingQuote = shippingFixture(actor, store, token, checkout);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<OrderService.Order>> calls = List.of(
					executor.submit(() -> { start.await(); return deliveryOrder(store, token, checkout.id(), customer, false, addressId, shippingQuote.quoteId()); }),
					executor.submit(() -> { start.await(); return deliveryOrder(store, token, checkout.id(), customer, false, addressId, shippingQuote.quoteId()); }));
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
		var shippingQuote = shippingFixture(actor, store, token, checkout);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<OrderService.Order> order = executor.submit(() -> {
				start.await(); return deliveryOrder(store, token, checkout.id(), customer, false, addressId, shippingQuote.quoteId());
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
