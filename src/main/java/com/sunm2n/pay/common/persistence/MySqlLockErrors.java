package com.sunm2n.pay.common.persistence;

import java.sql.SQLException;

/**
 * MySQL 의 락 실패를 벤더 에러 코드로 판정한다.
 *
 * <p>예외 타입으로 판정하지 않는 이유: 데드락(1213)은 JPA 경로에서 {@code CannotAcquireLockException} 으로 올라오고, 락 대기
 * 타임아웃(1205)도 같은 {@code PessimisticLockingFailureException} 계열로 번역될 수 있다. 둘은 DB 가 되돌리는 범위도, 재시도의
 * 의미도 다르다 ({@code docs/plan/S4.md} 6절). 그래서 원인 체인을 끝까지 따라가 {@link SQLException#getErrorCode()} 를
 * 본다. Spring 과 Hibernate 가 몇 겹으로 감싸도 맨 안쪽은 드라이버의 {@code SQLException} 이다.
 */
public final class MySqlLockErrors {

  /** {@code ER_LOCK_DEADLOCK}. 피해 트랜잭션 전체가 롤백된다. */
  public static final int DEADLOCK = 1213;

  /**
   * {@code ER_LOCK_WAIT_TIMEOUT}. 기본 설정({@code innodb_rollback_on_timeout=OFF})에서 DB 는 마지막 문장만
   * 롤백한다.
   */
  public static final int LOCK_WAIT_TIMEOUT = 1205;

  /** {@code ER_DUP_ENTRY}. unique 위반. 락 실패는 아니지만 S5 의 키 선점과 S6 의 중복 주문은 이 코드로 판정한다. */
  public static final int DUPLICATE_KEY = 1062;

  private MySqlLockErrors() {}

  public static boolean isDeadlock(Throwable throwable) {
    return hasErrorCode(throwable, DEADLOCK);
  }

  public static boolean isLockWaitTimeout(Throwable throwable) {
    return hasErrorCode(throwable, LOCK_WAIT_TIMEOUT);
  }

  /**
   * 주어진 unique 인덱스의 위반(1062)인가. 에러 코드만으로는 어느 제약인지 모르므로 메시지 끝의 인덱스 이름을 본다.
   *
   * <p>MySQL 8.0 의 메시지는 {@code Duplicate entry '...' for key '<table>.<index>'} 로 끝난다. 앞쪽의 중복 값에는
   * 사용자 입력이 들어가므로 끝부분만 본다.
   */
  public static boolean isDuplicateKey(Throwable throwable, String indexName) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && sql.getErrorCode() == DUPLICATE_KEY
          && sql.getMessage() != null
          && (sql.getMessage().endsWith("." + indexName + "'")
              || sql.getMessage().endsWith("'" + indexName + "'"))) {
        return true;
      }
      if (t.getCause() == t) {
        return false;
      }
    }
    return false;
  }

  private static boolean hasErrorCode(Throwable throwable, int errorCode) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getErrorCode() == errorCode) {
        return true;
      }
      if (t.getCause() == t) {
        return false;
      }
    }
    return false;
  }
}
