;; # mysql-connecter-j 시간
^{:nextjournal.clerk/visibility #{:hide-ns}}
(ns mysql
  (:require [nextjournal.clerk :as clerk]
            [next.jdbc :as jdbc]
            [clojure.datafy :refer :all]
            [clojure.java.shell :refer [sh]])
  (:import [java.time
            LocalDateTime
            ZonedDateTime]))

;; MySQL 과 프로그래밍 언어 사이에서 날짜,시간을 다룰 때 모르는 부분이 많습니다.
;; 어떤 환경이 영향을 끼치는지 목록을 만들어 ADR 을 만들 때, 시간을 저장하거나 읽을 때
;; 어떤 방법을 쓸 지 선택하는 순간에 자료로 사용할 수 있게 만듭니다. JVM 과 clojure next.jdbc 를
;; 쓰는 순간을 주로 다룹니다.
;;
;; 1. MySQL 의 데이터타입,
;; 2. MySQL 세션의 타임존 (서버, 클라이언트)
;; 3. mysql-connector-j 의 옵션
;; 4. next.jdbc 의 변환,
;; 이렇게 4가지가 영향을 주는 요인이 됩니다.

;; 각각을 일부분씩 묶어서 시험해보도록 하겠습니다. 먼저 `1. MySQL 의 데이터타입` 과
;; `2. MySQL 세션의 타임존 (서버, 클라이언트)` 를 묶고 그것만 고려한 상황입니다.
;; mysql server 와 mysql client 만을 써서 시험해봅니다. mysql reference manual 에서 챕터
;; [11.2.2 The DATE, DATETIME, and TIMESTAMP Types](https://dev.mysql.com/doc/refman/8.0/en/datetime.html) 를
;; 아주 일부분만 설명해 보면, 이렇습니다.
;;
;; mysql 은 `TIMESTAMP` datatype 을 storage 에 저장할 때 UTC 로 변환해 저장해 둡니다.
;; 꺼낼 때는 time zone 을 고려해서 변환합니다.
;;

;; 이렇게 mysql client cli 를 직접 실행해봅니다.
^:nextjournal.clerk/no-cache
(defn r1 [sql]
  (clerk/html
   [:pre (:out
          (sh "mysql" "-h" "127.0.0.1" "-u" "root" :in sql :in-enc "UTF8"))]))

(java.time.ZonedDateTime/now)

;; 현재시각은 20:31 분이고

(r1 "select now() as col")

;; MySQL 에서는 `now()` 로 UTC 시간을 보여줍니다. MySQL 의 session timezone, system timezone 을 확인해봅니다.

(r1 "show variables like '%time_zone%'")

;; system_time_zone 은 'UTC' 이고 time_zone 이 'SYSTEM' 으로 설정되어 있습니다.
;; `SET time_zone = '+09:00';` 으로 Per-session time zone 을 설정하고 다시 조회해봅니다.

(r1 "set time_zone = '+09:00'; select now() as col")

;; 이렇게 `TIMESTAMP` datatype 을 사용하면
;; mysql 에 client 로 접속하는 쪽에서 보기를 원하는 timezone 을
;; rendering 값을 받을 수 있습니다.

;; 자, mysql-connector-j 가 어떻게 session timezone 을 설정하고
;; `java.time.Instant`, `java.time.LocalDateTime` 을 읽어오거나 저장하는지 알아 봅니다.

;; mysql-connector-j 에서는
;; [6.3.11 Datetime types processing](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-connp-props-datetime-types-processing.html) 에
;; 에 적힌 것 처럼 `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants`
;; property 가 session timezone 설정과 mysql-connector-j 에서 처리하는 변환에 영향을 끼칩니다.
;; 이 property 들의 모든 조합 아래서 mysql-connector-j 이 date,time 관련 변환을 하는지는
;; [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/release%2F8.0/src/test/java/testsuite/simple/DateTimeTest.java#L3962-L3964) 에서
;; 볼 수 있습니다. [1]
;;
;; 하지만 그렇게 따라가는 것이 좀 어려웠습니다. mysql-connector-j 에서 사용하는
;; `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` property 의
;; default 값 아래 어떻게 되는지 예제를 보려고 합니다.
;;
;; 시험해 볼 때 next.jdbc 에서 해주는 변환을 그림에서 빼기 위해
;; DateTimeTest.java 처럼 mysql-connector-j (== JDBC) 의 구현만으로
;; 시험합니다.
;;

;; DataSource 를 이렇게 가져옵니다.
;; com.mysql.jdbc.jdbc2.optional.MysqlDataSource 을 써서 하나?
;; DataSource 에서 Connection 으로도 바꿔야 하고 뭐가 많다.
;; 그러지 말자.
;;
;; next.jdbc 에서 변환하는 부분을 찾아보자.
;;   next.jdbc 안에도 시간 변경과 관련한 테스트가 있다.
;;   따라가다 보니 postgresql jdbc driver 에서 특정 conversion 을
;;   지원안해서 생긴 문제라고 하는 듯 싶다.
;;
;; 그 문제를 확인하기 위한 test 가 date_time_test.clj 이다.
;; https://github.com/seancorfield/next-jdbc/blob/develop/test/next/jdbc/date_time_test.clj
;;
;;
;; https://github.com/seancorfield/next-jdbc/issues/73#issuecomment-553021972
;;
;; Different databases have different SQL types for representing
;; dates/times, with and without timezones,
;; so there is no "one size fits all" that
;; next.jdbc could "enforce" for all programs.
;;


;;
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




;; prepared statement 에 parameter 로 들어가는 것도 영향을 주는 요인일까?

;; 내가 쓰고 나서 그래도 답을 하고 싶은 것은
;;  그래서 mysql timestamp field 이면 jvm 의 zoneddatetime 으로 변환되는 건가?
;;  혹시 localdatetime 으로 나오는 건가?
;;  혹시 localdatetime 으로 나오면 jvm 에서 무슨 zone offset 이라고 생각하고 inst 로 바꾸면 되나?

;; 그래서 지금있는 datetime 이나 timestamp 필드에 지금 설정으로 select 를 한다고 하면
;; jvm 에 무슨 타입이 보이게 되는 건가?


;; mysql 에서 timezone offset 이 붙은 리터럴을 8.0.19 부터 지원하기 시작했다.
;; 이것은 jdbc 의 prepared statement 와 관련이 있을까?

;; mysql connector j 안에서 어떤 변환을 해주고 있을까?

;; next.jdbc 에서는 어떤 변환을 해주려고 시도하고 있나?


;; 테이블 생성없이 select 를 바로해서 값을 보자.
;; CAST 를 하는 것으로 타입이 잘 보이는 거 겠지?

;; 하는 길에 DEFAULT CURRUNT_TIMESTAMP 와 ON UPDATE CURRUNT_TIMESTAMP 의 기능도 시험해보자.
;; 한데 나중에 글에서는 빼자.

;; 몰랐다가 배운 것은 무엇인가? timestamp 가 어디서 부터 어디까지 표현할 수 있는가?

;; preserveInstant 옵션이 끼치는 영향은 그래서 뭐라고?


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


;; [1] [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/dd61577595edad45c398af508cf91ad26fc4144f/src/test/java/testsuite/simple/DateTimeTest.java#L4405) 에서
;; testSymmetricInstantRetrieval 함수를 보면
;; `#{java.sql.Timestamp, java.util.Date, java.time.ZonedDateTime}` 를 `#{DATETIME, TIMESTAMP, VARCHAR` 필드에
;; insert 하고 select 했을 때 같은 Instant 을 얻는지 시험하는 코드를 볼 수 있습니다.
;;
