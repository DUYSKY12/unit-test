package com.DATN.Bej.service.order;

import com.DATN.Bej.dto.request.cartRequest.CreateOrderRequest;
import com.DATN.Bej.dto.request.cartRequest.OrderItemRequest;
import com.DATN.Bej.dto.request.cartRequest.OrderItemsUpdateRequest;
import com.DATN.Bej.dto.request.order.UpdateOrderStatusRequest;
import com.DATN.Bej.dto.response.cart.OrderDetailsResponse;
import com.DATN.Bej.dto.response.order.OrderStatusUpdateResponse;
import com.DATN.Bej.entity.cart.OrderItem;
import com.DATN.Bej.entity.cart.Orders;
import com.DATN.Bej.entity.identity.User;
import com.DATN.Bej.entity.product.ProductAttribute;
import com.DATN.Bej.exception.AppException;
import com.DATN.Bej.exception.ErrorCode;
import com.DATN.Bej.mapper.product.OrderMapper;
import com.DATN.Bej.repository.UserRepository;
import com.DATN.Bej.repository.product.CartItemRepository;
import com.DATN.Bej.repository.product.OrderItemRepository;
import com.DATN.Bej.repository.product.OrderRepository;
import com.DATN.Bej.repository.product.ProductAttributeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    ProductAttributeRepository productAttributeRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    OrderMapper orderMapper;

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    SimpMessagingTemplate messagingTemplate;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    OrderService orderService;

    User mockUser;
    ProductAttribute mockProductAttribute;

    @BeforeEach
    void setUp() {
        // Mock User - Người dùng giả lập để test
        mockUser = new User();
        mockUser.setId("USER_01");
        mockUser.setPhoneNumber("0912345678");

        // Mock Product Attribute - Sản phẩm giả lập với giá 150,000đ
        mockProductAttribute = new ProductAttribute();
        mockProductAttribute.setId("ATT01");
        mockProductAttribute.setFinalPrice(150000.0);
    }

    @AfterEach
    void tearDown() {
        // Dọn dẹp Security Context sau mỗi test để tránh ảnh hưởng lẫn nhau
        SecurityContextHolder.clearContext();
    }

    /**
     * Helper: Giả lập người dùng đang đăng nhập với số điện thoại đã cho
     */
    private void mockSecurityContext(String phoneNumber) {
        SecurityContext context = mock(SecurityContext.class);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(phoneNumber, null);
        when(context.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(context);
    }

    // ==========================================
    // TC_ORDER_001: Tạo mới đơn hàng thành công
    // ==========================================
    @Test
    void createNewOrder_Success() {
        // Arrange - Chuẩn bị dữ liệu đầu vào hợp lệ
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("USER_01");

        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductAttId("ATT01");
        itemRequest.setQuantity(2);
        request.setItems(Collections.singletonList(itemRequest));

        Orders mockOrder = new Orders();
        mockOrder.setId("ORDER_01");
        mockOrder.setType(0); // Loại đơn: Mua bán

        OrderItem mockOrderItem = new OrderItem();
        mockOrderItem.setQuantity(2);

        when(userRepository.findById("USER_01")).thenReturn(Optional.of(mockUser));
        when(orderMapper.toOrder(request)).thenReturn(mockOrder);
        when(productAttributeRepository.findById("ATT01")).thenReturn(Optional.of(mockProductAttribute));
        when(orderMapper.toOrderItem(itemRequest)).thenReturn(mockOrderItem);
        when(orderRepository.save(any(Orders.class))).thenAnswer(i -> {
            Orders savedOrder = i.getArgument(0);
            savedOrder.setId("ORDER_01");
            return savedOrder;
        });
        when(orderMapper.toOrderDetailsResponse(any(Orders.class))).thenReturn(new OrderDetailsResponse());

        // Act - Gọi hàm cần kiểm thử
        OrderDetailsResponse result = orderService.createNewOrder(request);

        // Assert - Kiểm tra tính đúng đắn
        assertThat(result).isNotNull();
        // (CheckDB) Xác nhận DB được lưu đúng: tổng tiền = 150000, đúng user, đúng số item
        verify(orderRepository).save(argThat(order ->
            order.getTotalPrice() == 150000.0 &&
            order.getUser().getId().equals("USER_01") &&
            order.getOrderItems().size() == 1
        ));
        // Xác nhận Event được bắn để gửi thông báo (Firebase/Socket)
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    // ==========================================
    // TC_ORDER_002: Tạo đơn hàng thất bại - User không tồn tại
    // ==========================================
    @Test
    void createNewOrder_UserNotFound_ThrowsException() {
        // Arrange
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("USER_UNKNOWN");

        when(userRepository.findById("USER_UNKNOWN")).thenReturn(Optional.empty());

        // Act & Assert
        // (Rollback) Khi lỗi xảy ra, DB không bị tác động - save() không bao giờ được gọi
        AppException exception = assertThrows(AppException.class, () -> orderService.createNewOrder(request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_EXISTED);
        verify(orderRepository, never()).save(any());
    }

    // ==========================================
    // TC_ORDER_003: Cập nhật trạng thái đơn hàng thành công
    // ==========================================
    @Test
    void updateOrderStatus_Success() {
        // Arrange
        String orderId = "ORDER_01";
        UpdateOrderStatusRequest request = new UpdateOrderStatusRequest();
        request.setStatus(1); // Chuyển sang: Đã xác nhận
        request.setNote("Giao hàng nhanh nhé");

        mockSecurityContext("0912345678");

        Orders existingOrder = new Orders();
        existingOrder.setId(orderId);
        existingOrder.setStatus(0); // Hiện tại: Chờ xử lý
        existingOrder.setUser(mockUser);
        existingOrder.setOrderNotes(new ArrayList<>());

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));
        when(orderRepository.save(any(Orders.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        OrderStatusUpdateResponse result = orderService.updateOrderStatus(orderId, request);

        // Assert
        assertThat(result.getNewStatus()).isEqualTo(1);
        assertThat(result.getMessage()).isEqualTo("Order status updated successfully");

        // (CheckDB) Xác nhận lưu DB: status đúng, note được ghi log, đúng order
        verify(orderRepository).save(argThat(order ->
            order.getStatus() == 1 &&
            order.getOrderNotes().size() == 1 &&
            order.getOrderNotes().get(0).getNote().equals("Giao hàng nhanh nhé")
        ));
        // Xác nhận WebSocket broadcast được gọi 2 lần (tới user + admin)
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
        // Xác nhận Firebase Event được phát
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    // ==========================================
    // TC_ORDER_004: Cập nhật trạng thái không hợp lệ (ngoài vùng 0-5)
    // ==========================================
    @Test
    void updateOrderStatus_InvalidStatus_ThrowsException() {
        // Arrange
        String orderId = "ORDER_01";
        UpdateOrderStatusRequest request = new UpdateOrderStatusRequest();
        request.setStatus(10); // Giá trị vô lý, ngoài vùng [0-5]

        mockSecurityContext("0912345678");

        Orders existingOrder = new Orders();
        existingOrder.setId(orderId);
        existingOrder.setStatus(0);
        existingOrder.setUser(mockUser);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(userRepository.findByPhoneNumber("0912345678")).thenReturn(Optional.of(mockUser));

        // Act & Assert
        // (Rollback) Khi status lỗi, đơn hàng KHÔNG được lưu - đảm bảo tính toàn vẹn DB
        AppException exception = assertThrows(AppException.class,
                () -> orderService.updateOrderStatus(orderId, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_KEY);
        verify(orderRepository, never()).save(any());
    }
}
