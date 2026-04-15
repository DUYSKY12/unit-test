package com.DATN.Bej.service.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.DATN.Bej.dto.response.cart.CartItemResponse;
import com.DATN.Bej.entity.cart.CartItem;
import com.DATN.Bej.entity.identity.User;
import com.DATN.Bej.entity.product.Product;
import com.DATN.Bej.entity.product.ProductAttribute;
import com.DATN.Bej.entity.product.ProductVariant;
import com.DATN.Bej.exception.AppException;
import com.DATN.Bej.exception.ErrorCode;
import com.DATN.Bej.mapper.product.CartItemMapper;
import com.DATN.Bej.mapper.product.OrderMapper;
import com.DATN.Bej.repository.UserRepository;
import com.DATN.Bej.repository.product.CartItemRepository;
import com.DATN.Bej.repository.product.OrderRepository;
import com.DATN.Bej.repository.product.ProductAttributeRepository;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    ProductAttributeRepository productAttributeRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    OrderRepository ordersRepository;

    @Mock
    OrderMapper orderMapper;

    @Mock
    CartItemMapper cartItemMapper;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    CartService cartService;

    private User mockUser;
    private ProductAttribute mockProductA;

    @BeforeEach
    void setUp() {
        // Mock Security Context cho mọi Tests (Lấy SĐT user đang đăng nhập)
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getName()).thenReturn("0912345678");
        SecurityContextHolder.setContext(securityContext);

        // Khởi tạo mock data (User)
        mockUser = new User();
        mockUser.setId("USR01");
        mockUser.setPhoneNumber("0912345678");

        // Khởi tạo mock data (ProductAttribute)
        Product product = new Product();
        product.setName("iPhone 15");

        ProductVariant variant = new ProductVariant();
        variant.setColor("Titan Tự Nhiên");
        variant.setProduct(product);

        mockProductA = new ProductAttribute();
        mockProductA.setId("ATT01");
        mockProductA.setFinalPrice(25000000.0);
        mockProductA.setVariant(variant);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==========================================
    // TC_CART_001: Thêm sản phẩm MỚI vào giỏ hàng thành công
    // ==========================================
    @Test
    void addToCart_Success_NewItem() {
        // Arrange
        String attId = "ATT01";
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        when(productAttributeRepository.findById(attId)).thenReturn(Optional.of(mockProductA));
        when(cartItemRepository.findByUser_IdAndProductA_Id(mockUser.getId(), attId)).thenReturn(null);

        // Giả lập hàm lưu vào DB
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));

        // Giả lập hàm mapper
        CartItemResponse responseDto = new CartItemResponse();
        when(cartItemMapper.toCartItemResponse(any(CartItem.class))).thenReturn(responseDto);

        // Act
        CartItemResponse result = cartService.addToCart(attId);

        // Assert
        assertThat(result).isNotNull();
        // Kiểm chứng xem khi chưa có thì tạo mới quantity là 1
        verify(cartItemRepository, times(1)).save(argThat(cartItem -> 
            cartItem.getQuantity() == 1 &&
            cartItem.getPrice() == 25000000.0 &&
            cartItem.getColor().equals("Titan Tự Nhiên")
        ));
    }

    // ==========================================
    // TC_CART_002: Tự động CỘNG DỒN nếu SP đã có trong giỏ
    // ==========================================
    @Test
    void addToCart_Success_ExistedItem_Merge() {
        // Arrange
        String attId = "ATT01";
        CartItem existingCartItem = new CartItem();
        existingCartItem.setId("CART01");
        existingCartItem.setUser(mockUser);
        existingCartItem.setProductA(mockProductA);
        existingCartItem.setQuantity(2); // Đã có 2 cái trong giỏ
        
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        when(productAttributeRepository.findById(attId)).thenReturn(Optional.of(mockProductA));
        when(cartItemRepository.findByUser_IdAndProductA_Id(mockUser.getId(), attId)).thenReturn(existingCartItem);

        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));
        when(cartItemMapper.toCartItemResponse(any(CartItem.class))).thenReturn(new CartItemResponse());

        // Act
        cartService.addToCart(attId);

        // Assert
        // Số lượng phải tăng lên: 2 + 1 = 3
        verify(cartItemRepository, times(1)).save(argThat(cartItem -> cartItem.getQuantity() == 3));
    }

    // ==========================================
    // TC_CART_003: Thêm thất bại do Không tìm thấy User
    // ==========================================
    @Test
    void addToCart_Fail_UserNotFound() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addToCart("ATT01"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_EXISTED);
    }
    
    // ==========================================
    // TC_CART_004: Thêm thất bại do Sản phẩm (Attribute) không tồn tại
    // ==========================================
    @Test
    void addToCart_Fail_ProductNotFound() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        when(productAttributeRepository.findById("LOST_ATT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addToCart("LOST_ATT"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHENTICATED); // Theo code hiện tại ném ra unauthenticated
    }

    // ==========================================
    // TC_CART_005: Update số lượng nhỏ hơn hoặc bằng 0
    // ==========================================
    @Test
    void updateCartItemQuantity_Fail_QuantityZeroOrNegative() {
        assertThatThrownBy(() -> cartService.updateCartItemQuantity("CART01", 0))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_KEY);
    }
    
    // ==========================================
    // TC_CART_006: Chặn user A update/xóa giỏ hàng của user B
    // ==========================================
    @Test
    void updateCartItemQuantity_Fail_NotOwner() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        
        CartItem cartItemOfSomeoneElse = new CartItem();
        User hacker = new User();
        hacker.setId("HACKER001");
        cartItemOfSomeoneElse.setUser(hacker);

        when(cartItemRepository.findById("CART01")).thenReturn(Optional.of(cartItemOfSomeoneElse));

        assertThatThrownBy(() -> cartService.updateCartItemQuantity("CART01", 5))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHENTICATED);
    }

    // ==========================================
    // TC_CART_007: Xem giỏ hàng thành công
    // ==========================================
    @Test
    void viewCart_Success() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        when(cartItemRepository.findAllByUserId(mockUser.getId())).thenReturn(java.util.Collections.emptyList());
        
        var result = cartService.viewCart();
        assertThat(result).isNotNull();
    }

    // ==========================================
    // TC_CART_008: Xem giỏ hàng thất bại - Không tìm thấy user
    // ==========================================
    @Test
    void viewCart_Fail_UserNotFound() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cartService.viewCart())
                .isInstanceOf(AppException.class);
    }

    // ==========================================
    // TC_CART_009: Cập nhật số lượng giỏ hàng thành công
    // ==========================================
    @Test
    void updateCartItemQuantity_Success() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        
        CartItem cartItem = new CartItem();
        cartItem.setUser(mockUser);
        cartItem.setProductA(mockProductA);
        cartItem.setQuantity(1);
        
        when(cartItemRepository.findById("CART01")).thenReturn(Optional.of(cartItem));
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(i -> i.getArgument(0));
        when(cartItemMapper.toCartItemResponse(any(CartItem.class))).thenReturn(new CartItemResponse());
        
        var result = cartService.updateCartItemQuantity("CART01", 5);
        assertThat(result).isNotNull();
        verify(cartItemRepository, times(1)).save(argThat(c -> c.getQuantity() == 5));
    }

    // ==========================================
    // TC_CART_010: Xóa item khỏi giỏ hàng thành công
    // ==========================================
    @Test
    void removeFromCart_Success() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        
        CartItem cartItem = new CartItem();
        cartItem.setUser(mockUser);
        when(cartItemRepository.findById("CART01")).thenReturn(Optional.of(cartItem));
        
        cartService.removeFromCart("CART01");
        verify(cartItemRepository, times(1)).delete(cartItem);
    }

    // ==========================================
    // TC_CART_011: Xóa toàn bộ giỏ hàng thành công
    // ==========================================
    @Test
    void clearCart_Success() {
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        
        java.util.List<CartItem> cartItems = new java.util.ArrayList<>();
        cartItems.add(new CartItem());
        when(cartItemRepository.findAllByUserId(mockUser.getId())).thenReturn(cartItems);
        
        cartService.clearCart();
        verify(cartItemRepository, times(1)).deleteAll(cartItems);
    }
}
