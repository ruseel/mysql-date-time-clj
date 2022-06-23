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
;; 이렇게 4가지 요인이 영향을 줍니다.

;; 각각을 시험해보도록 하겠습니다. 먼저 `1. MySQL 의 데이터타입` 과
;; `2. MySQL 세션의 타임존 (서버, 클라이언트)` 만 고려한 상황입니다.
;;
;; mysql server 와 mysql client 만을 써서 시험해봅니다.
;;
;; mysql reference manual 에서 챕터
;; [11.2.2 The DATE, DATETIME, and TIMESTAMP Types](https://dev.mysql.com/doc/refman/8.0/en/datetime.html) 를
;; 아주 일부분만 설명해 보면, 이렇습니다. mysql 은 `TIMESTAMP` datatype 을 storage 에 저장할 때
;; UTC 로 변환해 저장해 둡니다. 꺼낼 때는 time zone 을 고려해서 변환합니다.
;; `DATETIME` datatype 은 변환없이 그대로 저장하고 꺼냅니다.
;;

(java.time.ZonedDateTime/now)

현재 시각입니다. JVM 의 user.timezone 대로 사용자의 편의를 위해 rendering 해서 문자열을 보여줍니다.

^:nextjournal.clerk/no-cache
(defn r1 [sql]
  (clerk/html
   [:pre (:out
          (sh "mysql" "-h" "127.0.0.1" "-u" "root" :in sql :in-enc "UTF8"))]))

(r1 "select now() as col")

;; MySQL 에서는 `now()` 로 UTC 시간을 보여줍니다. MySQL 의 session timezone, system timezone 을 확인해봅니다.

(r1 "show variables like '%time_zone%'")

;; system_time_zone 은 'UTC' 이고 time_zone 이 'SYSTEM' 으로 설정되어 있습니다.
;;
;; `SET time_zone = '+09:00';` 으로 Per-session time zone 을 설정하고 다시 조회해봅니다.

(r1 "set time_zone = '+09:00'; select now() as col")

;; 이렇게 `TIMESTAMP` datatype 을 사용하면, `now()`는 timestamp 입니다,
;; mysql 에 client 로 접속하는 쪽에서 보기를 원하는 timezone 으로
;; rendering 한 문자열을 볼 수 있습니다.

;; 여기까지는 mysql 과 mysql cli 클라이언트가 어떻게 동작하는가? 에 대한 이야기였습니다.
;;
;; mysql-connector-j 은 JDBC, 일종의 java interface 모음을 지원하는 라이브러리 입니다.
;; 꽤 규격화된 interface 를 지원하지만 그 구현체로서 시간처리를 어떻게 할지는
;; 구현체다마 아주 아주 약간 달라진다고 볼 수 있습니다. 그린랩스에서도 mysql-connector-j
;; 8.0.22 와 8.0.23 사이에서 달라지 동작을 목격한 적이 있습니다.
;;
;; 한데 JVM 의 TimeZone 설정, JDK 의 날짜+시간 클래스들, MySQL 의 wire-level protocol,
;; mysql 의 session timezone 설정이 섞여서 무척 헷갈립니다.
;;
;; 비교적 설명하기 쉬운 것 부터 알아봅니다. JVM 의 날짜+시간 클래스 중에 권장(?) 클래스 부터 알아봅니다.
;;
;; java.time.Instant 이 mysql-connector-j 의 문서 [Preserving Time Instant](https://docs.oracle.com/cd/E17952_01/connector-j-8.0-en/connector-j-time-instants.html)
;; 에서 말하는 instant 를 표현할 때 가장 적합한 클래스라고 가정해봅니다.
;; 문서 Preserving Time Instant 에서 개념 `Instant` 를 이렇게 설명합니다.
;;
;; A time instant is a specific moment on a time-line. A time instant is said to be
;; preserved when it always refers to the same point in time when its value
;; is being stored to or retrieved from a database,
;; no matter what time zones the database server and the clients are operating in.
;;
;; 그리고 java.time.Instant 는 ns `clojure.instant` 도 존재하고
;; tagged literal `#inst` 도 존재하니 clojure 에서도
;; java.time.Instant 를 사용하는 것이 Ideomatic 하다고 쳐 봅니다. next.jdbc [2] 에서도
;; java.time.Instant 를 관용적으로 잘 처리해줍니다. protocl `SettableParameter` 에서 확장해 줍니다.
;;
;; `java.util.Date` 는 JDK 1.0 부터 존재하던 클래스입니다. 옛 클래스이고 java.time package 의
;; 어떤 클래스와 딱 1:1 대응된다고 말하기 어려우니 clojure 의 simple 과는 거리가 먼 class 라고 치고
;; 심각하게 않는 것이 좋다고 칩니다.
;;
;; `java.util.Calander` 는 JDK 1.1 에서 탄생했고, `java.util.Date`의 기능의 일부를 좀 더 올바르게
;; 구현하는 듯 싶습니다. mysql-connector-j 에서 wire-level bytearray 를 해석하는 코드에서도
;; Calander 를 사용하는 것을 볼 수 있었습니다.
;;
;; `java.sql.Timestamp` 는 MySQL 의 datatype `TIMESTAMP` 와 정확히 1:1 대응하는
;; 개념 `instant` 를 저장하는 용도로 쓰는 클래스입니다.
;;
;; `java.time.ZoneDateTime` 은 (Instant + TimeZone) 이라고 이해할 수 있습니다.
;; Instant.atZone(ZoneId) 로 ZonedDateTime 으로 변환할 수 있습니다. 특별히 더 좋을 것이 없어보이니
;; 프로그램 내부에서는 문자열로 변환하기 직전까지 java.time.Instant 을 value 로 사용하는 것이 좋아보입니다.
;; next.jdbc 에서 `java.time.Instant` 를 특별 취급해 주는 것을 보면서 그렇게 생각하게 되었습니다.
;;
;; 여기까지가 소위 instant date-time types 였습니다.
;; non-instant date-time types 로 `java.time.LocalDateTime` 가 있습니다. [3]
;;
;; 문서 `Preserving Time Instant` 에서 발췌하면
;;
;; Therefore, do not pass instant date-time types (java.util.Calendar, java.util.Date,
;; java.time.OffsetDateTime, java.sql.Timestamp) to non-instant date-time types
;; (for example, java.sql.DATE, java.time.LocalDate, java.time.LocalTime, java.time.OffsetTime)
;; or vice versa, when working with the server.
;;
;; 이렇게 instant date-time types 와 non-instant date-time types 를 섞지 말라고 합니다.
;;
;;
;; mysql-connector-j 8.0.22 부터
;; [6.3.11 Datetime types processing](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-connp-props-datetime-types-processing.html) 에
;; 에 적힌 것 처럼 `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants`
;; property 가 session timezone 설정과 mysql-connector-j 에서 처리하는 변환에 영향을 끼칩니다.
;;
;; 이 property 들의 모든 조합 아래서 mysql-connector-j 이 date,time 관련 변환을 하는지는
;; [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/release%2F8.0/src/test/java/testsuite/simple/DateTimeTest.java#L3962-L3964) 에서
;; 볼 수 있습니다. [1]
;;
;; 하지만 그렇게 따라가는 것이 좀 어려웠습니다. XXX mysql-connector-j 의 connection property
;; `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` property 의
;; default 값 아래 어떻게 되는지 예제를 보려고 합니다.
;;
;; next.jdbc 에서는 날짜, 시간과 관련해서 변환을 최대한 JDBC 레이어에 맡깁니다.
;; java.time.Instant 를 protocol SettableParameter 에서 지원해 주는 것은
;; 아주 약간 있는 그리고 next.jdbc 에서 지원해 줄 수 있는 최소한이라 봐야 합니다.
;;
;; https://github.com/seancorfield/next-jdbc/issues/73#issuecomment-553021972
;;
;; Different databases have different SQL types for representing
;; dates/times, with and without timezones,
;; so there is no "one size fits all" that
;; next.jdbc could "enforce" for all programs.
;;

;; mysql-connector-j 의 connection property
;; `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` 에 따라

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
;; [2] https://github.com/seancorfield/next-jdbc/blob/develop/src/next/jdbc/date_time.clj#L40-L62
;;
;; [3] 뭐, 그냥 짐작컨데 그런 것 같습니다. 거짓이라도 제가 멘탈모델을 구성하는데는 도움이 되었어요.
