package com.sunm2n.pay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunm2n.pay.idempotency.IdempotencyKeyClaimedException;
import com.sunm2n.pay.idempotency.IdempotencyKeyReusedException;
import com.sunm2n.pay.idempotency.IdempotencyKeyStore;
import com.sunm2n.pay.idempotency.IdempotencyRecord;
import com.sunm2n.pay.idempotency.IdempotencyStatus;
import com.sunm2n.pay.payment.domain.Payment;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 취소의 멱등성 조율자 — unique + insert-first. 컨트롤러는 이 클래스만 부른다 ({@code docs/plan/S5.md} 4.3).
 *
 * <pre>
 * key == null → 기존 경로. 저장하지 않는다
 * tx1  claim (키 INSERT, 실제로 먼저 나간다)
 *      PaymentService.cancel   본선 canceller 가 tx1 에 참여
 *      스냅샷 → complete        커밋된 결과와 저장된 응답이 같은 시점의 값
 * 커밋 성공 → 스냅샷
 * 선점 충돌(1062) → tx1 은 이미 롤백. tx2(readOnly) 에서 저장된 응답을 읽는다
 * </pre>
 *
 * <p>선점·저장·재생은 여기서 하고, <b>무엇을 실행하는가</b>와 <b>요청 hash</b> 는 호출자가 넘긴다 ({@link #cancel(Long, String,
 * String, Supplier)}). S6 에서 주문 기반 취소가 같은 규칙을 쓰게 되면서 분리했다 ({@code docs/plan/S6.md} 4.3). {@code
 * paymentKey} 취소는 {@link PaymentService#cancel} 을 넘긴다. 그 메서드는 스스로 트랜잭션을 열지 않고 본선 canceller 에 위임하므로,
 * tx1 안에서 부르면 canceller 가 tx1 에 참여한다. 넘기는 실행도 같은 조건을 지켜야 한다 — 새 트랜잭션을 열면 키와 취소가 따로 커밋된다. 조율자 자신은
 * 트랜잭션이 없다. {@link TransactionTemplate} 이 커밋까지 마친 뒤에만 반환하므로 커밋 실패는 성공 응답이 되지 않는다.
 *
 * <p>업무 실패(409 등)는 tx1 전체를 롤백시키고 키도 함께 사라진다. 저장하는 것은 성공 응답뿐이다. 대기 상한 초과({@code
 * IdempotencyKeyInUseException})와 1213 은 tx1 밖으로 그대로 나가 전체를 롤백시킨다 — 1213 은 현재 규약대로 500 이다 (4.6).
 */
public class IdempotentCancelCoordinator {

  private final PaymentService paymentService;
  private final IdempotencyKeyStore store;
  private final TransactionTemplate claimTransaction;
  private final TransactionTemplate replayTransaction;
  private final ObjectMapper objectMapper;

  public IdempotentCancelCoordinator(
      PaymentService paymentService,
      IdempotencyKeyStore store,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    this.paymentService = paymentService;
    this.store = store;
    this.claimTransaction = new TransactionTemplate(transactionManager);
    this.replayTransaction = new TransactionTemplate(transactionManager);
    this.replayTransaction.setReadOnly(true);
    this.objectMapper = objectMapper;
  }

  /** 키 없는 경로. 저장하지 않는다. */
  public CancelOutcome cancel(
      Long merchantId, String paymentKey, long cancelAmount, String reason) {
    return CancelOutcome.ok(
        PaymentSnapshot.from(paymentService.cancel(merchantId, paymentKey, cancelAmount, reason)));
  }

  /**
   * 키가 있으면 같은 키로 커밋되는 취소는 최대 한 건이고, 그 성공 응답이 재생된다.
   *
   * @param idempotencyKey 형식 검증을 마친 키. {@code null} 이면 키 없는 경로다
   */
  public CancelOutcome cancel(
      Long merchantId, String idempotencyKey, String paymentKey, long cancelAmount, String reason) {
    if (idempotencyKey == null) {
      return cancel(merchantId, paymentKey, cancelAmount, reason);
    }
    return cancel(
        merchantId,
        idempotencyKey,
        CancelFingerprint.hash(paymentKey, cancelAmount, reason),
        () -> paymentService.cancel(merchantId, paymentKey, cancelAmount, reason));
  }

  /**
   * 주문 기반 취소 (S6). 선점·저장·재생 규칙은 {@code paymentKey} 취소와 같고, fingerprint 만 {@link
   * OrderCancelFingerprint} 다. 키 INSERT 가 payment 잠금 읽기보다 먼저 나간다 — insert-first.
   *
   * @param idempotencyKey 형식 검증을 마친 키. {@code null} 이면 키 없는 경로다
   */
  public CancelOutcome cancelByOrder(
      Long merchantId, String idempotencyKey, String orderId, long cancelAmount, String reason) {
    if (idempotencyKey == null) {
      return CancelOutcome.ok(
          PaymentSnapshot.from(
              paymentService.cancelByOrder(merchantId, orderId, cancelAmount, reason)));
    }
    return cancel(
        merchantId,
        idempotencyKey,
        OrderCancelFingerprint.hash(orderId, cancelAmount, reason),
        () -> paymentService.cancelByOrder(merchantId, orderId, cancelAmount, reason));
  }

  /**
   * 선점 → 실행 → 저장. 선점 충돌이면 저장된 응답을 재생한다.
   *
   * @param idempotencyKey 형식 검증을 마친 키. {@code null} 이 아니다
   * @param requestHash 같은 요청인지 판정하는 fingerprint. 취소 경로마다 구성이 다르다
   * @param cancellation 취소 실행. tx1 에 참여해야 하므로 스스로 새 트랜잭션을 열지 않는다
   */
  public CancelOutcome cancel(
      Long merchantId, String idempotencyKey, String requestHash, Supplier<Payment> cancellation) {
    try {
      return claimTransaction.execute(
          status -> {
            long id =
                store.claim(merchantId, CancelFingerprint.OPERATION, idempotencyKey, requestHash);
            Payment payment = cancellation.get();
            CancelOutcome outcome = CancelOutcome.ok(PaymentSnapshot.from(payment));
            store.complete(id, outcome.status(), write(outcome.body()));
            return outcome;
          });
    } catch (IdempotencyKeyClaimedException claimed) {
      return replayTransaction.execute(status -> replay(merchantId, idempotencyKey, requestHash));
    }
  }

  /**
   * 저장된 응답을 되돌려준다. 정리·삭제 경로가 없으므로 선점 충돌 뒤에는 행이 반드시 있다. 선행이 롤백했다면 후속 INSERT 는 위반 없이 성공했을 것이다 (4.3).
   * 그래서 행 없음을 재선점으로 흡수하지 않고 예상 밖 상태로 드러낸다.
   */
  private CancelOutcome replay(Long merchantId, String idempotencyKey, String requestHash) {
    IdempotencyRecord record =
        store
            .find(merchantId, CancelFingerprint.OPERATION, idempotencyKey)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "선점 충돌 뒤 키 행이 없다. 정리 경로가 없으므로 불가능한 상태다. key=" + idempotencyKey));
    if (!record.requestHash().equals(requestHash)) {
      throw new IdempotencyKeyReusedException(idempotencyKey);
    }
    if (record.status() != IdempotencyStatus.COMPLETED) {
      throw new IllegalStateException(
          "커밋된 키 행이 COMPLETED 가 아니다. S5 는 단일 트랜잭션이라 불가능한 상태다. key=" + idempotencyKey);
    }
    return new CancelOutcome(record.responseStatus(), read(record.responseBody()));
  }

  private String write(PaymentSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("응답 스냅샷을 직렬화하지 못했다", e);
    }
  }

  private PaymentSnapshot read(String body) {
    try {
      return objectMapper.readValue(body, PaymentSnapshot.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("저장된 응답 스냅샷을 읽지 못했다", e);
    }
  }
}
