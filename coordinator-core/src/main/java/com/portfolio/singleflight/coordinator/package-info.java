/**
 * SingleFlight 코디네이터 핵심 추상화 — 동일 키에 대한 동시 호출자를 한 번의
 * 작업 수행으로 합치는 패턴(coalescing)의 Java 인터페이스 / DTO 묶음.
 *
 * <p>이 패키지의 모든 타입은 JSpecify {@link org.jspecify.annotations.NullMarked}
 * 로 선언되어, 별도 표시가 없는 모든 타입 사용은 <b>non-null</b>로 간주된다.
 * null이 합법적인 자리에는 명시적으로 {@link org.jspecify.annotations.Nullable}
 * 을 단다.
 *
 * <p>JSpecify는 컴파일 타임 nullability 명세이며, 런타임 검사가 아니다. 실제
 * NPE 차단은 public API 진입점의 {@code Objects.requireNonNull} 가드가 담당한다
 * (관련 contract 테스트는 {@link com.portfolio.singleflight.coordinator.adapter
 * .InProcessSingleFlightCoordinator} 의 [7] 입력 계약 그룹 참조).
 */
@NullMarked
package com.portfolio.singleflight.coordinator;

import org.jspecify.annotations.NullMarked;
