;; # mysql-connecter-j 시간
^{:nextjournal.clerk/visibility #{:hide-ns}}
(ns mysql
  (:require [nextjournal.clerk :as clerk]
            [next.jdbc :as jdbc]
            [clojure.datafy :refer :all]
            [clojure.java.shell :refer sh])
  (:import [java.time
            LocalDateTime
            ZonedDateTime]))

;; MySQL 과 프로그래밍 언어 사이에서 날짜,시간을 다룰 때 모르는 부분이 많다.
;; 어떤 환경이 영향을 끼치는지 목록을 만들어 ADR 을 만들 때, 시간을 저장하거나 읽을 때
;; 어떤 방법을 선택할지 고민하면서 자료로 사용할 수 있게 하자.
;;
;; (MySQL 의 데이터타입, MySQL 세션의 타임존 (서버, 클라이언트)),
;; mysql-connector-j 의 옵션, next.jdbc 의 변환,
;; 이렇게 4가지가 영향을 주는 요인이다.

;; mysql 만 있을 때,
;; mysql + mysql-connector-j 가 끼여 있을 때
;; mysql + mysql-connector-j + next.jdbc 가 있을 때
;;
;; 이렇게 3가지가 다르다고 볼 수 있을까? 그런 것 같다.


;; java 안에서 java.sql.Timestamp 는 어떻게 동작하게 되나?
;;   jst 는 timezone 을 system timezone(== local timezone) 이라고 해석하는 클래스인가?
;; 특히나 next.jdbc 의 SettableParameter 가 뭐라고 생각하고 동작하게 되는 것인가?


;; MySQL DataType 중에 DATETIME, TIMESTAMP 는 어떻게 차이가 나나?
;; Greenlabs ADR 에서

;; mysql-connector-j 에서는 이 설명에 나오는 부분이 아주 심하게 영향을 끼친다.
;; https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-time-instants.html
;;

;; instant datatype 과 non-instant datatype 의 차이가 존재한다.
;; 섞어서 쓰면 예상치 못한 결과가 나올 수 있다고 한다.




prepared statement 에 parameter 로 들어가는 것도 영향을 주는 요인일까?

;; 내가 쓰고 나서 그래도 답을 하고 싶은 것은
;;  그래서 mysql timestamp field 이면 jvm 의 zoneddatetime 으로 변환되는 건가?
;;  혹시 localdatetime 으로 나오는 건가?
;;  혹시 localdatetime 으로 나오면 jvm 에서 무슨 zone offset 이라고 생각하고 inst 로 바꾸면 되나?

그래서 지금있는 datetime 이나 timestamp 필드에 지금 설정으로 select 를 한다고 하면
jvm 에 무슨 타입이 보이게 되는 건가?


mysql 에서 timezone offset 이 붙은 리터럴을 8.0.19 부터 지원하기 시작했다.
이것은 jdbc 의 prepared statement 와 관련이 있을까?

mysql connector j 안에서 어떤 변환을 해주고 있을까?

next.jdbc 에서는 어떤 변환을 해주려고 시도하고 있나?


테이블 생성없이 select 를 바로해서 값을 보자.
CAST 를 하는 것으로 타입이 잘 보이는 거 겠지?

하는 길에 DEFAULT CURRUNT_TIMESTAMP 와 ON UPDATE CURRUNT_TIMESTAMP 의 기능도 시험해보자.
한데 나중에 글에서는 빼자.

몰랐다가 배운 것은 무엇인가? timestamp 가 어디서 부터 어디까지 표현할 수 있는가?

preserveInstant 옵션이 끼치는 영향은 그래서 뭐라고?


;; mysql 의 date and time data types 에 대해서 좀 더 이해하자.
;; 2차 저작으로 남겨두는 것은 뭐가 좋을까?
;;

;; 이 파일을 실행하는 아래 환경에서 실행했다.
;; JVM 에서 시간을 얻으려면 이렇게 한다.

(System/getProperty "user.timezone")
(ZonedDateTime/now)
(LocalDateTime/now)

;; MySQL 로 접속해 보자

(def db {:dbtype "mysql" :dbname "test" :user "root"})
(first (vals (jdbc/execute-one! db ["select now()"])))

;; MySQL 에서 now() 로 얻은 값이 JVM 에서 LocalDateTime 으로 얻어집니다.


;; JVM 의 영향없이 mysql cli 로 직접 접속해서 시험해봅니다.
;;
;;

(defn run-1 [sql]
  (println
   (:out
    (sh "mysql" "-h" "127.0.0.1" "-u" "root"
        :in "select now()" :in-enc "UTF8"))))

(run-1 "select now()")


;; mysql 에서 timestamp 를 얻는 방법이 있나?
;; session 설정이 다르면 다른 값으로 나오나?
;;
;;

;; mysql 의 date, time, datetime, timestamp 는 무엇인가?
;;

;; JVM 에서 이렇게 얻은 값은 이 설정에 영향을 많이 받습니다.
;;
;; https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-time-instants.html
