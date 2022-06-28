;; # mysql-connecter-j 시간
^{:nextjournal.clerk/visibility #{:hide-ns}}
(ns mysql
  (:require [nextjournal.clerk :as clerk]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]
            [clojure.datafy :refer :all]
            [clojure.java.shell :refer [sh]])
  (:import [java.util TimeZone]
           [java.time LocalDateTime ZonedDateTime ZoneId]))

;; MySQL 과 프로그래밍 언어 사이에서 날짜,시간을 다룰 때 모르는 부분이 많습니다.
;;
;; 어떤 환경이 영향을 끼치는지 목록을 만들어 ADR 을 만들 때, 시간을 저장하거나 읽을 때
;; 어떤 방법을 쓸 지 선택하는 순간, 자료로 참고할 수 있게 만듭니다. JVM 과 clojure next.jdbc 를
;; 쓰는 순간을 주로 다룹니다.
;;
;; 1. MySQL 의 데이터타입
;; 2. MySQL 세션의 타임존 (서버, 클라이언트)
;; 3. mysql-connector-j 의 옵션
;; 4. next.jdbc 의 변환
;;
;; 이렇게 4가지 요인이 영향을 줍니다.

;; 일부씩 시험해 보겠습니다. 먼저 `1. MySQL 의 데이터타입` 과
;; `2. MySQL 세션의 타임존 (서버, 클라이언트)` 만 고려한 상황입니다.
;;
;; mysql server 와 mysql client 만으로 시험해봅니다.
;;
;; mysql reference manual 에서 챕터
;; [11.2.2 The DATE, DATETIME, and TIMESTAMP Types](https://dev.mysql.com/doc/refman/8.0/en/datetime.html) 를
;; 아주 일부분만 발췌하면, 이렇습니다. mysql 은 datatype `TIMESTAMP` 을 storage 에 저장할 때
;; UTC 로 변환해 저장합니다. 꺼낼 때는 time zone 을 고려해서 변환합니다.
;; `DATETIME` datatype 은 변환없이 그대로 저장하고 꺼냅니다.
;;

(java.time.ZonedDateTime/now)

;; 현재 시각입니다. JVM 의 user.timezone 대로, `(TimeZone/getDefault)` 를 참조해서
;; 사용자의 편의를 위해 rendering 해서 문자열을 보여줍니다.


^:nextjournal.clerk/no-cache
(defn r1 [sql]
  (clerk/html
   [:pre (:out
          (sh "mysql" "-h" "127.0.0.1" "-u" "root" "test" :in sql :in-enc "UTF8"))]))

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

;; 여기까지는 mysql server 와 mysql cli 클라이언트가 어떻게 동작하는가? 에 대한 이야기였습니다.
;;
;; mysql-connector-j 은 JDBC 의 구현체입니다.
;; 잘 정의된 JDBC interface 를 지원하지만, 그 구현체가 시간처리를 어떻게 할지는
;; 정의되지 않았다고 볼 수 있고 그러니 구현체마다 달라진다고 볼 수 있습니다.
;; 그린랩스에서도 mysql-connector-j 8.0.22 와 8.0.23 사이에서 달라진 날짜 시간 동작을
;; 주빈님이 목격한 적이 있습니다.
;;
;; 여기서 부터 JVM 의 TimeZone 설정, JDK 의 날짜+시간 클래스들, MySQL 의 wire-level protocol,
;; mysql 의 session timezone 설정이 섞여 이해하기가 어렵습니다.
;;
;; 비교적 설명하기 쉬운 것 부터 알아봅니다. JVM 의 날짜+시간 클래스 중에 권장(?) 클래스 부터 알아봅니다.
;;
;; java.time.Instant 이 mysql-connector-j 의 문서 [Preserving Time Instant](https://docs.oracle.com/cd/E17952_01/connector-j-8.0-en/connector-j-time-instants.html)
;; 에서 말하는 instant 를 표현할 때 가장 적합한 클래스라고 가정해봅니다. 문서 Preserving Time Instant 에서
;; 개념 `Instant` 를 이렇게 설명합니다.
;;
;; A time instant is a specific moment on a time-line. A time instant is said to be
;; preserved when it always refers to the same point in time when its value
;; is being stored to or retrieved from a database,
;; no matter what time zones the database server and the clients are operating in.
;;
;; 그리고 java.time.Instant 는 ns `clojure.instant` 도 존재하고
;; tagged literal `#inst` 도 존재하니 clojure 에서도
;; java.time.Instant 를 사용하는 것이 Ideomatic 하다고 가정해 봅니다.
;; next.jdbc [2] 에서도 java.time.Instant 를 관용적으로 잘 처리해줍니다.
;; next.jdbc 에서 protocl `SettableParameter` 에서 java.time.Instant 를 확장(extend-protocol) 해
;; 지원합니다.
;;
;; `java.util.Date` 는 JDK 1.0 부터 존재하던 클래스입니다. 옛 클래스이고 java.time package 의
;; 어떤 클래스와 딱 1:1 대응된다고 말하기 어려우니 clojure 의 `Simple` 과는 거리가 먼 class 라고 치고
;; 심각하게 고려하지 않습니다.
;;
;; `java.util.Calander` 는 JDK 1.1 에서 탄생했고, `java.util.Date`의 기능의 일부를 좀 더 올바르게
;; 구현하는 듯 싶습니다. mysql-connector-j 에서 wire-level bytearray 를 해석하는 코드에서도
;; Calander 를 사용하는 것을 볼 수 있었습니다.
;;
;; `java.sql.Timestamp` 는 MySQL 의 datatype `TIMESTAMP` 와 정확히 1:1 대응하는
;; 개념 `instant` 를 저장하는 용도로 쓰는 클래스입니다.
;;
;; `java.time.ZoneDateTime` 은 (Instant + ZoneId(==TimeZone)) 이라고 이해할 수 있습니다.
;; 코드 `Instant.atZone(ZoneId)` 로 ZonedDateTime 으로 변환합니다. 더 좋을 것이 없어보이니
;; 프로그램 내부에서는 문자열로 변환하기 직전까지 java.time.Instant 을 Value 로 사용하는 정책이 좋아보입니다.
;; next.jdbc 에서 `java.time.Instant` 를 특별 취급해 주는 것을 보면서 그렇게 생각하게 되었습니다.
;;
;; 여기까지가 소위 instant date-time types 였습니다. java.time.Instant, java.util.Date, java.util.Calander,
;; java.sql.Timestamp 모두 instant date-time types 입니다.
;;
;; non-instant date-time types 로 `java.time.LocalDateTime` 가 있습니다.
;; 다른 non-instant date-time types 도 있겠지만 고려하지 말아 봅니다. [3]
;;
;; 문서 `Preserving Time Instant` 에서 발췌하면
;; 이렇게 instant date-time types 와 non-instant date-time types 를 섞지 말라고 합니다.
;;
;; Therefore, do not pass instant date-time types (java.util.Calendar, java.util.Date,
;; java.time.OffsetDateTime, java.sql.Timestamp) to non-instant date-time types
;; (for example, java.sql.DATE, java.time.LocalDate, java.time.LocalTime, java.time.OffsetTime)
;; or vice versa, when working with the server.
;;
;; 섞었을 때 예상하기 무척 어려운 동작이 생긴다고 하니 되도록이면 그러지 말도록 노력해봅니다.
;;
;; mysql-connector-j 8.0.23 부터
;; [6.3.11 Datetime types processing](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-connp-props-datetime-types-processing.html) 에
;; 에 적힌 것 처럼 `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants`
;; property 가 session timezone 설정과 mysql-connector-j 에서 처리하는 변환에 영향을 끼칩니다.
;; `forcedConnectionTimeZoneToSession`, `preserveInstants` 가 8.0.23 부터 생겼습니다.
;;
;; 이 property 들의 모든 조합 아래서 mysql-connector-j 이 date,time 관련 변환을 어떻게 하는지
;; [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/release%2F8.0/src/test/java/testsuite/simple/DateTimeTest.java#L3962-L3964) 에서
;; 볼 수 있습니다. [1]
;;
;; 하지만 그렇게 따라가는 것이 좀 어려웠습니다. 그러니 mysql-connector-j 8.0.28 에서 각 property 의
;; default 값 아래 어떻게 실행되는지 예제를 보려고 합니다. 신선하이에서는 아무 property 도 설정하지 않으니
;; default 값으로 씁니다. connectionTimeZone, forceConnectionTimeZoneToSession, preserveInstants 의
;; default 값은 순서대로 N/A, false, true 입니다.
;;
;; 시험할 때 next.jdbc 를 같이 사용합니다. next.jdbc 에서는 날짜, 시간과 관련해서 변환을
;; 최대한 JDBC 레이어에 맡깁니다.
;; java.time.Instant 를 protocol SettableParameter 에서 지원해 주는 것은
;; next.jdbc 에서 지원해 줄 수 있는 최대한으로 정말 작은 부분이라고 봐야 합니다.
;;
;; Sean Corfield 가 이런 글을 남긴 적이 있습니다.
;;
;; ```https://github.com/seancorfield/next-jdbc/issues/73#issuecomment-553021972
;; Different databases have different SQL types for representing
;; dates/times, with and without timezones,
;; so there is no "one size fits all" that
;; next.jdbc could "enforce" for all programs.
;; ```
;;
;; 윗 글을 봐서도 소스코드를 살펴봐도 Instant 값에 영향을 줄 만한 변환을 next.jdbc 에서 하는 경우는 없습니다.
;; 그러니 이 글에서 next.jdbc 도 포함해 시험하지만 mysql-connector-j 만을 시험하는 것과 거의 같습니다.
;;
;; mysql server 는 docker 8.0.29 를 사용해 구성했습니다.
;; datetime 필드가 있는 table `datetime_t` 을 생성합니다.
;;
^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result}
(r1 "create table datetime_t (id int, dt datetime);")

;; JVM default 타임존을 #{"Asia/Seoul" "UTC"} 로 선택한 다음
;; 각각 어떻게 되는지 봅니다.
;;
;; "2020-01-01 10:10:10" 을 문자열로,
;; 그리고 LocalDateTime 으로 만들어서,
;; 그리고 ZonedDateTime (UTC) 으로 만들어서,
;; 그리고 그 ZonedDateTime 을 Instant 로 바꿔서, 저장합니다.
;;

^{:nextjournal.clerk/visibility #{:hide}}
(clerk/example
 (def db {:dbtype "mysql" :dbname "test" :user "root"})

 (def str-dt "2020-01-01 10:10:10")
 (def ldt (java.time.LocalDateTime/of 2020 1 1 10 10 10))
 (def zdt (java.time.ZonedDateTime/of 2020 1 1 10 10 10 0 (ZoneId/of "UTC")))
 (def inst (.toInstant zdt))

 (defn test-dt-t []
   (doall
    (for [[id v] [[1 str-dt]
                  [2 ldt]
                  [3 zdt]
                  [4 inst]]]
      (next.jdbc/execute!
       db
       ["insert into datetime_t(id, dt) values (?, ?)" id v])))))

^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result}
(r1 "delete from datetime_t;")

;; datetime_t 테이블로 insert 하는 test-dt-t 함수를 만들었고
;;
;; 이제 default timezone 을 바꿔가면서 실행합니다.
;; UTC 로 설정해 두고 실행합니다.
;;
^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(clerk/example
 (TimeZone/setDefault (TimeZone/getTimeZone "UTC"))
 (test-dt-t))

;; Asia/Seoul 로 설정하고 실행합니다.
^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(clerk/example
 (TimeZone/setDefault (TimeZone/getTimeZone "Asia/Seoul"))
 (test-dt-t))


;; 다시 TimeZone 은 UTC 로 원복해둡니다.
;;
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(TimeZone/setDefault (TimeZone/getTimeZone "UTC"))


;; 이제 next.jdbc 를 통해 select 를 하고 어떤 객체가 반환되는지 봅니다.
;; datatype `DATETIME` 일 때는 ADR 을 작성할 때의 의도와 같이
;; JVM 의 TimeZone 이 무엇이던지 그대로 LocalDateTime 객체를 리턴합니다.
;; (여기서는 TimeZone 설정해서 select 하는 부분은 생략했습니다)

^:nextjournal.clerk/no-cache
(clerk/table
 (next.jdbc/execute! db ["select * from datetime_t"]))

;; java.time.LocalDateTime 을 리턴하고 [4]
;;
;; JVM 의 TimeZone 이 `Asia/Seoul` 일 때
;; ZonedDateTime 과 Instant 로 insert 한 Value 가 시간 `19:10` 으로
;; `UTC` 일 때 insert 한 값 시간 `10:10`과 다르게 나옵니다.
;;

;; timestamp 필드가 있는 table `timestamp_t` 을 생성합니다.
;;
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result}
(r1 "create table timestamp_t (id int, ts timestamp);")

^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result}
(r1 "delete from timestamp_t")

;; JVM default 타임존을 #{"Asia/Seoul" "UTC"} 로 선택한 다음
;; 각각 어떻게 되는지 봅니다.
;;
;; "2020-01-01 10:10:10" 을 문자열로,
;; 그리고 LocalDateTime 으로 만들어서,
;; 그리고 ZonedDateTime (UTC) 으로 만들어서,
;; 그리고 그 ZonedDateTime (UTC) 을 Instant 로 바꿔서, 저장합니다.
;; 앞서와 동일 합니다.

^:nextjournal.clerk/no-cache
(defn insert-to-ts-t []
  (doall
   (for [[id v] [[1 str-dt]
                 [2 ldt]
                 [3 zdt]
                 [4 inst]]]
     (next.jdbc/execute!
      db
      ["insert into timestamp_t(id, ts) values (?, ?)" id v]))))

^:nextjournal.clerk/no-cache
(clerk/example

 (TimeZone/setDefault (TimeZone/getTimeZone "UTC"))
 (insert-to-ts-t)

 (TimeZone/setDefault (TimeZone/getTimeZone "Asia/Seoul"))
 (insert-to-ts-t)

 )

;; 이제 next.jdbc 를 통해 select 를 하고 어떤 객체가 반환되는지 봅니다.
;;

^:nextjournal.clerk/no-cache
(TimeZone/setDefault (TimeZone/getTimeZone "UTC"))
^:nextjournal.clerk/no-cache
(clerk/table
 (next.jdbc/execute! db ["select * from timestamp_t"]))

^:nextjournal.clerk/no-cache
(TimeZone/setDefault (TimeZone/getTimeZone "Asia/Seoul"))

^:nextjournal.clerk/no-cache
(clerk/table
 (next.jdbc/execute! db ["select * from timestamp_t"]))

;; `TIMESTAMP` field 를 통해서 `#inst "2020-01-01T01:10:10.000000000-00:00"` 값이
;; UTC 일 때도 Asia/Seoul 일 때도 전부 다 같게 리턴됩니다.
;; mysql-connector-j 의 preserveInstants = `true` 기능이 잘 동작한다고 볼 수 있습니다.
;; class 는 java.sql.Timestamp 입니다. clojure 에서 java.sql.Timestamp 일 때
;; `#inst` 로 tagging 된 tagged literal 을 인쇄해 줍니다.

(def inst-x
  (:timestamp_t/ts
   (next.jdbc/execute-one! db ["select * from timestamp_t"])))

(def inst-y (read-string (pr-str inst-x)))


;; TBD java.sql.Timestamp 를 inst 로 취급하면 clojure.java-time 에서는 시간연산들을
;; java.sql.Timestamp 에 대해서도 잘 지원하나?

;; inst-x (== java.sql.Timestamp) 는 이렇게 pr-str 에서도 잘 지원하고
;; `clojure.core/read` 에서도 잘 지원합니다.


;; # QnA

;; next.jdbc 에서 prepared statement 에 parameter 로 넣을 때도 next.jdbc 에서 변환을 할까?
;;
;; 아니다. 그렇게 강제로 변환하는 부분은 없다고 보는 것이 좋다. 전부 mysql-connector-j 에서
;; 하는 구현에서 만든 변환이라고 봐야 한다.

;; 그래서 어떻게 하면 좋을까? recipe 가 있다면 무엇인가?
;;
;; TBD

;; mysql 에서 timezone offset 이 붙은 리터럴을 8.0.19 부터 지원하기 시작했다. 이 기능이
;; jdbc 의 prepared statement 와 setParameter 처리 후에 일어나는 변환들과 연관이 있을까?
;;
;; 없다.
;;

;; 그래서 작성 과정 중에 새로 배운 것은 무엇인가?
;;
;; TBD
;;

;; preserveInstant 옵션이 끼치는 영향은 그래서 무엇인가?
;;
;; TBD


;; # 참고
;;
;; * [철도 시간표가 유닉스 시간이 되기까지](https://parksb.github.io/article/39.html)
;;
;; # 주석

;; [1] [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/dd61577595edad45c398af508cf91ad26fc4144f/src/test/java/testsuite/simple/DateTimeTest.java#L4405) 에서
;; testSymmetricInstantRetrieval 함수를 보면
;; `#{java.sql.Timestamp, java.util.Date, java.time.ZonedDateTime}` 를 `#{DATETIME, TIMESTAMP, VARCHAR` 필드에
;; insert 하고 select 했을 때 같은 Instant 을 얻는지 시험하는 코드를 볼 수 있습니다.
;;
;; [2] https://github.com/seancorfield/next-jdbc/blob/develop/src/next/jdbc/date_time.clj#L40-L62
;;
;; [3] 뭐, 그냥 짐작컨데 그런 것 같습니다. 거짓이라도 제가 멘탈모델을 구성하는데는 도움이 되었어요.
;;
;; [4] TBD 아마 mysql-connector-j 에서 ResultSet.getObject 이고 mysql datatype 이 DateTime 이면 LocalDateTime 을 리턴하게 코딩되어 있을 것 같다. 찾아보자.
