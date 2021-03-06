package com.lin.sleeve.service;

import com.lin.sleeve.core.LocalUser;
import com.lin.sleeve.core.enumeration.CouponStatus;
import com.lin.sleeve.core.enumeration.OrderStatus;
import com.lin.sleeve.core.money.IMoneyDiscount;
import com.lin.sleeve.dto.OrderDTO;
import com.lin.sleeve.dto.SkuInfoDTO;
import com.lin.sleeve.exception.ExceptionCodes;
import com.lin.sleeve.exception.http.NotFoundException;
import com.lin.sleeve.exception.http.ParameterException;
import com.lin.sleeve.logic.CouponChecker;
import com.lin.sleeve.logic.OrderChecker;
import com.lin.sleeve.model.Coupon;
import com.lin.sleeve.model.Order;
import com.lin.sleeve.model.OrderSku;
import com.lin.sleeve.model.Sku;
import com.lin.sleeve.repository.CouponRepository;
import com.lin.sleeve.repository.OrderRepository;
import com.lin.sleeve.repository.SkuRepository;
import com.lin.sleeve.repository.UserCouponRepository;
import com.lin.sleeve.util.CommonUtil;
import com.lin.sleeve.util.OrderUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.transaction.Transactional;

/**
 * @author Ztiany
 * Email ztiany3@gmail.com
 * Date 2021/2/4 14:57
 */
@Service
public class OrderService {

    @Autowired
    private SkuService skuService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private IMoneyDiscount moneyDiscount;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${sleeve.order.max-sku-limit}")
    private Integer maxSkuLimit;

    @Value("${sleeve.order.pay-time-limit}")
    private Integer payTimeLimit;

    public OrderChecker isOK(Long uid, OrderDTO orderDTO) {
        System.out.println("orderDTO = " + orderDTO);
        //???????????????????????? 0 ??????
        if (orderDTO == null || orderDTO.getFinalTotalPrice().compareTo(new BigDecimal("0")) <= 0) {
            throw new ParameterException(ExceptionCodes.C_50011);
        }

        //??????????????? sku
        List<Long> skuIdList = orderDTO.getSkuInfoList()
                .stream()
                .map(SkuInfoDTO::getId)
                .collect(Collectors.toList());
        List<Sku> skuList = skuService.getSkuListByIds(skuIdList);

        //???????????????
        Long couponId = orderDTO.getCouponId();
        CouponChecker couponChecker = null;
        if (couponId != null) {
            //???????????????
            Coupon coupon = couponRepository.findById(couponId).orElseThrow(() -> new NotFoundException(ExceptionCodes.C_40004));
            //?????????????????????????????????
            userCouponRepository.findFirstByUserIdAndCouponIdAndStatus(uid, couponId, CouponStatus.AVAILABLE.getValue()).orElseThrow(() -> new NotFoundException(ExceptionCodes.C_50006));
            //????????????????????????
            couponChecker = new CouponChecker(coupon, moneyDiscount);
        }

        OrderChecker orderChecker = new OrderChecker(orderDTO, skuList, couponChecker, maxSkuLimit);
        orderChecker.isOK();

        return orderChecker;
    }

    @Transactional
    public Long placeOrder(Long uid, OrderDTO orderDTO, OrderChecker orderChecker) {
        //????????????
        Order order = new Order();
        order.setOrderNo(OrderUtil.makeOrderNo());
        order.setTotalPrice(orderDTO.getTotalPrice());
        order.setFinalTotalPrice(orderDTO.getFinalTotalPrice());
        order.setUserId(uid);
        order.setTotalCount(orderChecker.getTotalCount());
        order.setSnapImg(orderChecker.getLeaderImg());
        order.setSnapTitle(orderChecker.getLeaderTitle());
        order.setSnapAddress(orderDTO.getOrderAddress());
        order.setSnapItems(orderChecker.getOrderSkuList());
        order.setStatus(OrderStatus.UNPAID.value());
        Calendar now = Calendar.getInstance();
        //?????? PlacedTime ????????? CreateTime ????????????????????????????????? CreateTime??????????????????????????????????????????????????????
        order.setPlacedTime(now.getTime());
        //??????????????????????????????????????? payTimeLimit ??????????????????????????????????????????
        order.setExpiredTime(CommonUtil.addSomeSeconds(now, payTimeLimit).getTime());
        //????????????
        orderRepository.save(order);
        //????????????
        reduceOrderedStock(orderChecker);
        //???????????????
        if (orderDTO.getCouponId() != null) {
            writeOffCoupon(orderDTO.getCouponId(), uid, order.getId());
        }
        //????????????????????????????????????????????????????????????????????????????????????
        sendToRedis(orderDTO.getCouponId(), uid, order.getId());
        return order.getId();
    }

    private void sendToRedis(Long oid, Long uid, Long couponId) {
        String redisKey = OrderUtil.makeRedisKey(oid, uid, couponId);
        try {
            stringRedisTemplate.opsForValue().set(redisKey, "1", this.payTimeLimit, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
            //????????????????????????????????????????????????/Redis ???????????????????????????????????????
            //?????????????????????????????????????????????????????????????????????
        }
    }

    private void writeOffCoupon(Long couponId, Long uid, Long orderId) {
        int result = userCouponRepository.writeOffCoupon(couponId, uid, orderId);
        if (result != 1) {
            throw new ParameterException(ExceptionCodes.C_40012);
        }
    }

    private void reduceOrderedStock(OrderChecker orderChecker) {
        List<OrderSku> skus = orderChecker.getOrderSkuList();
        skus.forEach(orderSku -> {
            //reduceStock ???????????? sql ????????????????????????
            int result = skuRepository.reduceStock(orderSku.getId(), orderSku.getCount().longValue());
            if (result != 1) {
                throw new ParameterException(ExceptionCodes.C_50003);
            }
        });
    }

    public Page<Order> getUnpaid(Integer page, Integer count) {
        Long uid = LocalUser.getUser().getId();
        Pageable pageable = PageRequest.of(page, count, Sort.by("createTime").descending());
        return orderRepository.findByExpiredTimeGreaterThanAndStatusAndUserId(new Date(), OrderStatus.UNPAID.value(), uid, pageable);
    }

    public Page<Order> getByStatus(Integer status, Integer page, Integer count) {
        Long uid = LocalUser.getUser().getId();
        Pageable pageable = PageRequest.of(page, count, Sort.by("createTime").descending());
        if (status == OrderStatus.All.value()) {
            return orderRepository.findAllByUserId(uid, pageable);
        } else {
            return orderRepository.findAllByUserIdAndStatus(uid, status, pageable);
        }
    }

    public Optional<Order> getOrderDetail(Long oid) {
        Long uid = LocalUser.getUser().getId();
        return orderRepository.findFirstByUserIdAndId(uid, oid);
    }

    public void updateOrderPrepayId(Long orderId, String prepayId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ParameterException(ExceptionCodes.C_10007));
        order.setPrepayId(prepayId);
        orderRepository.save(order);
    }

}