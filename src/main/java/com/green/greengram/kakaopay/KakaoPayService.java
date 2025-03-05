package com.green.greengram.kakaopay;

import com.green.greengram.config.SessionUtils;
import com.green.greengram.config.constants.ConstKakaoPay;
import com.green.greengram.config.exception.CustomException;
import com.green.greengram.config.exception.PayErrorCode;
import com.green.greengram.config.security.AuthenticationFacade;
import com.green.greengram.entity.*;
import com.green.greengram.kakaopay.model.*;
import com.green.greengram.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoPayService {
    private final AuthenticationFacade authenticationFacade;
    private final ConstKakaoPay constKakaoPay;
    private final KakaoPayFeignClient kakaoPayFeignClient;
    private final OrderMasterRepository orderMasterRepository;

    private final ProductRepository productRepository;

    @Transactional
    public KakaoPayReadyRes postReady(KakaoPayReadyReq req) {
        if(req.getProductList().size() == 0) { throw new CustomException(PayErrorCode.NOT_EXISTED_PRODUCT_INFO);  }
        List<Long> productIds = req.getProductList().stream().mapToLong(item -> item.getProductId()).boxed().toList();
        // 들어온 것에서 productId값만 리스트로 파싱(재포장)
        // 위의 mapToLong 은 거기에 있는 원하는 Long 타입만 빼서 쓰는 것 boxed()는 LongStream이면 필요한 부분
        // 사실 이건 그냥 스트림으로 해도 된다.
        List<Product> productList = productRepository.findByProductIdIn(productIds);
        if(req.getProductList().size() != productList.size()) { throw new CustomException(PayErrorCode.NO_EXISTED_PRODUCT_INFO); }

        //Product 목록을 Map으로 변환하여 빠르게 검색 가능하게 만듦 Function.identity()는 객체의 멤버필드가 아닌 객체 자신을 말한다.
        Map<Long, OrderProductDto> orderProductMap = req.getProductList().stream().collect(Collectors.toMap(OrderProductDto::getProductId, Function.identity()));



        int totalAmount = productList.stream().reduce(0,
                (prev, item) ->  prev + (item.getProductPrice() * orderProductMap.get(item.getProductId()).getQuantity()),
                Integer::sum);
        // reduce List를 하나로 만들고 싶을때 사용
        // 첫번째 인자 : 초기값
        // 두번째 인자 : BiFunction(파라미터가 2개라는 뜻) Implements한 객체 주소값 여기서 첫번째는 이전 리턴값 두번째는 stream 자식이 순차적으로 넘어옴
        // 두번째에서 스트림의 첫 실행시 특정값이 리턴 이 리턴값은 두번째는 prev 로 들어가는 것 첫번째 실행에선 0(초기값)이 들어가는 것
        // 마지막구간이 없으면 prev가 Product 객체를 요구함


        User signedUser = User.builder()
                .userId(authenticationFacade.getSignedUserId())
                .build();


        OrderMaster orderMaster = OrderMaster.builder() // A
                .user(signedUser)
                .totalAmount(totalAmount)
                .orderStatusCode(OrderStatusCode.READY)
                .build();

        for(Product item : productList) {
            OrderProductIds ids = OrderProductIds.builder()
                    .productId(item.getProductId())
                    .build();
            // 아직 master 를 insert 하기 전이라 orderId를 넣을 수 없다.
            OrderProduct orderProduct = OrderProduct.builder() // C
                    .ids(ids)
                    .product(item)
                    .quantity(orderProductMap.get(item.getProductId()).getQuantity())
                    .unitPrice(item.getProductPrice())
                    .build();

            orderMaster.addOrderProduct(orderProduct); // C와 A를 양방향 연결(C에 필요)
        }

        orderMasterRepository.save(orderMaster); //자녀까지 같이 INSERT 된다(양방향 설정으로 인하여)

        String itemName = productList.get(0).getProductName();
        if(productList.size() > 1) {
            itemName += String.format(" 외 %d개", productList.size() - 1);
        }
        // 상품마다 갯수를 채크해주기 위한 부분(가장 앞의 상품 외 n개)

        KakaoPayReadyFeignReq feignReq = KakaoPayReadyFeignReq.builder()
                .cid(constKakaoPay.getCid())
                .partnerOrderId(orderMaster.getOrderId().toString()) //거래 PK값이 좋음
                .partnerUserId(String.valueOf(authenticationFacade.getSignedUserId())) //결제 유저 ID
                .itemName(itemName)
                .quantity(productList.size())
                .totalAmount(totalAmount)
                .taxFreeAmount(0)
                .approvalUrl(constKakaoPay.getApprovalUrl())
                .failUrl(constKakaoPay.getFailUrl())
                .cancelUrl(constKakaoPay.getCancelUrl())
                .build();


        KakaoPayReadyRes res = kakaoPayFeignClient.postReady(feignReq); // tid를 포함해서 리턴받음

        //세션에 결제 정보 저장 넣고있는 3개가 결제 시도시 같아야 되기 때문에 세션 저장 후 빼서 쓰기 위해
        KakaoPaySessionDto dto = KakaoPaySessionDto.builder()
                .tid(res.getTid())
                .partnerOrderId(feignReq.getPartnerOrderId())
                .partnerUserId(feignReq.getPartnerUserId())
                .build();

        SessionUtils.addAttribute(constKakaoPay.getKakaoPayInfoSessionName(), dto);
        log.info("tid: {}", res.getTid());
        return res;
    }

    @Transactional
    public KakaoPayApproveRes getApprove(KakaoPayApproveReq req) {
        //카카오페이 준비과정에서 세션에 저장한 고유번호(tid) 가져오기
        KakaoPaySessionDto dto = (KakaoPaySessionDto) SessionUtils.getAttribute(constKakaoPay.getKakaoPayInfoSessionName());
        log.info("결제승인 요청을 인증하는 토큰: {}", req.getPgToken());
        //log.info("결제 고유번호: {}", tid);

        KakaoPayApproveFeignReq feignReq = KakaoPayApproveFeignReq.builder()
                .cid(constKakaoPay.getCid())
                .tid(dto.getTid())
                .partnerOrderId(dto.getPartnerOrderId())
                .partnerUserId(dto.getPartnerUserId())
                .pgToken(req.getPgToken())
                .payload("테스트")
                .build();

        KakaoPayApproveRes res = kakaoPayFeignClient.postApprove(feignReq);
        log.info("res: {}", res);

        OrderMaster orderMaster = orderMasterRepository.findById(Long.parseLong(dto.getPartnerOrderId())).orElse(null);
        if(orderMaster != null) {
            orderMaster.setOrderStatusCode(OrderStatusCode.COMPLETED);
            orderMasterRepository.save(orderMaster);
        }
        return res;
    }
}
