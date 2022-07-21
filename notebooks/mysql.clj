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

;; MySQL 과 프로그래밍 언어를 오갈 때 날짜,시간을 다루면서 헷갈리는 부분이 많습니다.
;; 어떤 환경이 영향을 주는지 ADR 을 만들 때, 저장하거나 읽을 때,
;; 어떤 방법을 쓸지 선택하는 순간에 참고할 수 있게 적어 봅니다.
;; JVM 과 clojure next.jdbc 를 다룹니다.
;;
;; 1. MySQL 의 데이터타입
;; 2. MySQL 세션의 타임존 (서버, 클라이언트)
;; 3. mysql-connector-j 의 옵션
;; 4. next.jdbc 의 변환
;;
;; 이  4가지가 영향을 줄거라 예상합니다. 하나씩 추가하며 시험해 보겠습니다.
;; 먼저 `1. MySQL 의 데이터타입` 과 `2. MySQL 세션의 타임존 (서버, 클라이언트)` 만 고려한 상황입니다.
;;
;; mysql reference manual 에서 챕터
;; [11.2.2 The DATE, DATETIME, and TIMESTAMP Types](https://dev.mysql.com/doc/refman/8.0/en/datetime.html) 를
;; 일부분만 발췌했습니다.
;;
;; > mysql 은 datatype `TIMESTAMP` 을 storage 에 저장할 때
;; > UTC 로 변환해 저장합니다. 꺼낼 때는 time zone 을 고려해서 변환합니다.
;; > `DATETIME` datatype 은 변환없이 그대로 저장하고 꺼냅니다.

;; 이 clerk notebook 이 실행되는 현재 시각을 Instant/now 로 얻습니다.

^:nextjournal.clerk/no-cache
(java.time.Instant/now)

;; mysql cli 만을 해서 "select now()" 를 실행해봅니다.
;;

^:nextjournal.clerk/no-cache
(defn r1 [sql]
  (clerk/html
   [:pre (:out
          (sh "mysql" "-h" "127.0.0.1" "-u" "root" "test" :in sql :in-enc "UTF8"))]))

(r1 "select now() as col")

;; MySQL 에서는 `now()` 를 호출하니 UTC 시간을 보여줍니다.
;; MySQL 의 session timezone, system timezone 을 보면

(r1 "show variables like '%time_zone%'")

;; system_time_zone 은 'UTC' 이고 time_zone 이 'SYSTEM' 으로 설정되어 있습니다.
;;
;; `SET time_zone = '+09:00';` 으로 per-session time zone 을 설정하고 다시 조회해봅니다.

(r1 "set time_zone = '+09:00'; select now() as col")

;; 이렇게 `TIMESTAMP` datatype 을 사용하면,
;; mysql client 에서 원하는 timezone 으로 설정했을 때
;; 그 timezone 으로 rendering 한 문자열을 화면에 표시합니다.
;; (network wire protocol 수준에서 어떤 값으로 오가는지는 잘 모르지만) 여튼
;; 설정한 time_zone 으로 stdout 으로 rendering 해서 표시해 줍니다.

;; 이제 mysql-connector-j 를 더 해봅니다. mysql-connector-j 은 JDBC 의 구현체입니다.
;; 잘 정의된 JDBC interface 를 지원합니다. 하지만 그 구현체가 정한 시간 처리 방법은 구현체마다 다릅니다.
;; 심지어 mysql-connector-j 버전 마다도 다르다고 할 수 있습니다.
;; 그린랩스에서도 mysql-connector-j 8.0.22 와 8.0.23 사이에서 달라진 날짜 시간 동작을
;; 주빈님이 목격한 적이 있습니다.
;;
;; mysql-connector-j 가 들어가기 시작하면 JVM 의 TimeZone 설정, JDK 의 날짜+시간 클래스들, MySQL 의 wire-level protocol,
;; mysql 의 session timezone 설정이 섞여 이해가 어려워지기 시작합니다.
;;
;; 비교적 설명하기 쉬운 JVM 의 날짜+시간 클래스를 다뤄 봅니다.
;; mysql-connector-j 의 문서 [Preserving Time Instant](https://docs.oracle.com/cd/E17952_01/connector-j-8.0-en/connector-j-time-instants.html) 에서
;; 말하는 instant 를 표현할 때 가장 적합한 클래스가 java.time.Instant 라고 가정해봅니다. 이름도 같고 내용도 같지 않나 싶습니다.
;;
;; 문서 Preserving Time Instant 에서 개념 `Instant` 를 발췌하면 이렇습니다.
;;
;; > A time instant is a specific moment on a time-line. A time instant is said to be
;; > preserved when it always refers to the same point in time when its value
;; > is being stored to or retrieved from a database,
;; > no matter what time zones the database server and the clients are operating in.
;;
;; clojure 에서 edn extension 으로 지원하는 tag 는 `#inst`, `#uuid` 이렇게 단 2개 인데
;; `#inst` 가 Instant 를 표현하는 tag 입니다. namespace `clojure.instant` 도 존재하고
;; protocol `clojure.core/Inst` 도 있습니다. 그러니 clojure 에서 시간을 처리할 때
;; Instant 를 사용하는 것이 ideomatic 하다고 해봅니다. next.jdbc [2] 에서도 `java.time.Instant` 를 잘 처리해줍니다.
;; next.jdbc 에서 protocol `SettableParameter` 를 java.time.Instant 에 확장(extend-protocol) 해서 지원합니다.
;;
;; `java.util.Date` 는 JDK 1.0 부터 존재하던 클래스입니다. 고루한 클래스이고 java.time package 의
;; 어떤 클래스와 딱 1:1 대응된다고 말하기 어려우니 clojure 의 `Simple` 과는 거리가 먼 class 라고 쳐 봅니다.
;; clojure 에서도 `clojure.instant/read-timestamp-date` 지원하고 있지만
;; clojure 가 2008 년부터 만들어지기 시작했으니 그 때 필요했던 거겠지 하며
;; 왠만하면 우리도 쓰지 않도록 해봅니다.
;;
;; `java.util.Calander` 는 JDK 1.1 에서 탄생했고, `java.util.Date`의 기능의 일부를 좀 더 올바르게
;; 구현하는 듯 싶습니다. 이번 글을 적으면서 JDK javadoc 어디선가 그런 문구를 본 것 같아요.
;; mysql-connector-j 소스코드 안 에서도 wire-level bytearray 를 해석하는 코드에서
;; Calander 를 사용하는 것을 봤습니다. 이것도 이제는 java.time 안쪽의 다른 class 를 쓰는 것이 좋겠지 해봅니다.
;;
;; `java.sql.Timestamp` 는 MySQL 의 datatype `TIMESTAMP` 와 정확히 1:1 대응합니다.
;; 개념 `Instant` 를 저장하는 용도로 쓰는 클래스입니다.
;;
;; `java.time.ZoneDateTime` 은 (Instant + ZoneId(==TimeZone)) 라고 이해할 수 있습니다.
;; `Instant.atZone(ZoneId)` 함수를 호출하면 ZonedDateTime 을 Instant 로 변환할 수 있습니다.
;; ZonedDateTime 과 Instant 를 비교하면 Instant 가 더 좋아보이니 — 그렇죠?? —
;; 프로그램 내부에서는 문자열로 변환하기 직전까지, 외부로 값을 내보내기 직전까지, java.time.Instant 를
;; 사용하는 정책을 만들자 주장해봅니다. next.jdbc 에서 `java.time.Instant` 를 특별히 더 다뤄 주는 것을 보면서 그렇게 생각하게 되었습니다.
;; VCNC 에서는 아주 예전 코드에서 시간을 저장할 때 long 으로 createdAtMillis 처럼
;; 필드이름에 Millis suffix 를 붙이고 instant 를 long 으로 저장하고 protocol 상에서도 long 으로
;; 전달하는 관례가 있었습니다. 당시 CTO 였던 김영목님의 선택이라고 생각했었습니다.
;; Luke Vanderhart(@levanderhart)도 시간을 프로토콜로 왔다갔다 할 때는 long 값으로만 (millis since epoch) 으로 만
;; 쓴다고 트위터에 적어 준 것을 본 것 같습니다. 이런 접근도 아주 타당하지만
;; Clojure 에서는 edn 레벨에서 Instant 를 지원하니 clojure source 코드안에서 Instant 를 잘 썼으면 좋겠습니다.
;;
;; 여기까지가 소위 instant date-time types in java 였습니다.
;; java.time.Instant, java.util.Date, java.util.Calander, java.sql.Timestamp 모두
;; instant date-time types 입니다. non-instant date-time types 로 `java.time.LocalDateTime` 가 있습니다.
;; 다른 non-instant date-time types 도 있겠지만 고려하지 말아 봅니다. [3]
;;
;; 문서 `Preserving Time Instant` 에서 다시 발췌하면
;; 이렇게 instant date-time types 와 non-instant date-time types 있을 때 둘을 섞지 말라고 합니다.
;;
;; > Therefore, do not pass instant date-time types (java.util.Calendar, java.util.Date,
;; > java.time.OffsetDateTime, java.sql.Timestamp) to non-instant date-time types
;; > (for example, java.sql.DATE, java.time.LocalDate, java.time.LocalTime, java.time.OffsetTime)
;; > or vice versa, when working with the server.
;;
;; 섞었을 때 예상하기 무척 어려운 동작이 생긴다고 하니 되도록이면 그러지 말도록 노력해봅니다.
;;
;; mysql-connector-j 8.0.23 부터
;; [6.3.11 Datetime types processing](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-connp-props-datetime-types-processing.html) 에
;; 에서 말하길 `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants`
;; property 가 session timezone 설정과 mysql-connector-j 에서 처리하는 변환에 영향을 끼칩니다.
;; `forcedConnectionTimeZoneToSession`, `preserveInstants` 가 8.0.23 부터 생겼습니다.
;;
;; 이 property 들의 모든 조합 아래서 mysql-connector-j 이 date,time 관련 변환을 어떻게 하는지 그것을 어떻게 시함하고 있는지
;; [DateTimeTest.java](https://github.com/mysql/mysql-connector-j/blob/release%2F8.0/src/test/java/testsuite/simple/DateTimeTest.java#L3962-L3964) 에서
;; 볼 수 있습니다. [1] 하지만 그 코드를 이해하기가 어려웠습니다. 그러니 mysql-connector-j 8.0.28 에서 각 property 의
;; default 값을 썼을 때 실행 결과를 보려고 합니다. 신선하이에서는 아무 property 도 설정하지 않고 default 값을 씁니다.
;; `connectionTimeZone`, `forceConnectionTimeZoneToSession`, `preserveInstants` 의
;; default 값은 순서대로 N/A, false, true 입니다.
;;
;; 시험할 때 next.jdbc 를 mysql-connector-j 와 같이 사용합니다.
;; next.jdbc 에서는 날짜,시간과 변환을 최대한 JDBC 레이어에 맡깁니다.
;; java.time.Instant 를 protocol SettableParameter 에서 지원해 주는 것은
;; next.jdbc 에서 지원해 줄 수 있는 최대한으로 정말 작은 부분이라고 봐야 합니다.
;; Sean Corfield 가 [이런 글](https://github.com/seancorfield/next-jdbc/issues/73#issuecomment-553021972) 을 남긴 적이 있습니다.
;;
;; > Different databases have different SQL types for representing
;; > dates/times, with and without timezones,
;; > so there is no "one size fits all" that
;; > next.jdbc could "enforce" for all programs.
;;
;; 윗 글을 봐도 next.jdbc 소스를 봐도 Instant 에 영향을 줄 변조를 next.jdbc 에서 하는 경우는 없습니다.
;; 그러니 이제 next.jdbc 를 사용해 시험하는 것이 mysql-connector-j 만 시험하는 것과 동일합니다.
;;
;; mysql server 는 docker 로 버전 8.0.29 를 사용해 띄웠습니다.
;; datetime 필드가 있는 table `datetime_t` 을 생성합니다.
;;
^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result}
(r1 "create table datetime_t (id int, dt datetime);")

;; "2020-01-01 10:10:10" 을 문자열로 만들고,
;; 그리고 LocalDateTime 으로 만들어서,
;; 그리고 ZonedDateTime (UTC) 으로 만들어서,
;; 그리고 ZonedDateTime 을 Instant 로 바꿔서, 저장합니다.

^{:nextjournal.clerk/visibility #{:hide}}
(clerk/example
 (def db {:dbtype "mysql" :dbname "test" :user "root"})

 (def str-dt "2020-01-01 10:10:10")
 (def ldt (java.time.LocalDateTime/of 2020 1 1 10 10 10))
 (def zdt (java.time.ZonedDateTime/of 2020 1 1 10 10 10 0 (ZoneId/of "UTC")))
 (def inst (.toInstant zdt))

 (defn insert-to-datetime-t []
   (doall
    (for [[i v] [[1 str-dt]
                 [2 ldt]
                 [3 zdt]
                 [4 inst]]]
      (next.jdbc/execute!
       db
       ["insert into datetime_t(id, dt) values (?, ?)" i v])))))

^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result}
(r1 "delete from datetime_t;")

;; insert 하는 insert-to-datetime-t 함수를 만들었고, 그 함수를 default timezone 을 바꿔가면서 실행합니다. 먼저 UTC 로 설정한 후 실행(insert) 합니다.
;;
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(clerk/example
 (TimeZone/setDefault (TimeZone/getTimeZone "UTC"))
 (insert-to-datetime-t))

;; Asia/Seoul 로 설정하고 실행합니다.
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(clerk/example
 (TimeZone/setDefault (TimeZone/getTimeZone "Asia/Seoul"))
 (insert-to-datetime-t))

;; 다시 TimeZone 은 UTC 로 원복해둡니다.
;;
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result
  :nextjournal.clerk/no-cache true}
(TimeZone/setDefault (TimeZone/getTimeZone "UTC"))

;; 이제 next.jdbc 를 통해 select 를 하고 어떤 객체가 반환되는지 봅니다.
;; datatype `DATETIME` 일 때는 ADR 을 작성할 때의 의도와 같이
;; select 하는 JVM 의 TimeZone 과 상관없이 같은 LocalDateTime 객체를 리턴합니다.
;; (여기서는 TimeZone 설정해서 select 하는 부분은 생략했습니다.)

^:nextjournal.clerk/no-cache
(clerk/table
 (next.jdbc/execute! db ["select * from datetime_t"]))

;; 모두 java.time.LocalDateTime 의 인스턴스를 리턴합니다[4]. 다만 JVM TimeZone 을 `Asia/Seoul` 로 설정하고 insert 한 값은,
;; ZonedDateTime 과 Instant 로 insert 한 값만 select 했을 때 `19:10` 으로 보입니다. 나머지는 `10:10` 으로 보입니다.
;;
;; JVM TimeZone 을 `UTC` 로 설정하고 ZonedDateTime 과 Instant 로 insert 한 값은, 모두 `10:10` 로 보입니다.

;; timestamp 필드가 있는 table `timestamp_t` 을 생성합니다.
;;
^{:nextjournal.clerk/visibility #{:show}
  :nextjournal.clerk/viewer :hide-result}
(r1 "create table timestamp_t (id int, ts timestamp);")

^{:nextjournal.clerk/visibility #{:hide}
  :nextjournal.clerk/viewer :hide-result}
(r1 "delete from timestamp_t")

;; "2020-01-01 10:10:10" 을 문자열로,
;; 그리고 LocalDateTime 으로 만들어서,
;; 그리고 ZonedDateTime (UTC) 으로 만들어서,
;; 그리고 그 ZonedDateTime (UTC) 을 Instant 로 바꿔서,
;; timestamp_t 테이블에 저장합니다. 앞과 저장하는 테이블만 다를 뿐 동일 합니다.

^:nextjournal.clerk/no-cache
(defn insert-to-timestamp-t []
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
 (insert-to-timestamp-t)

 (TimeZone/setDefault (TimeZone/getTimeZone "Asia/Seoul"))
 (insert-to-timestamp-t))

;; 이제 next.jdbc 를 통해 select 를 하고 어떤 객체가 반환되는지 봅니다.

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

;; `TIMESTAMP` field 를 얻어오니, `#inst "2020-01-01T01:10:10.000000000-00:00"` 값이
;; JVM의 타임존이 `UTC` 일 때도 `Asia/Seoul` 일 때도 리턴됩니다.
;; mysql-connector-j 의 preserveInstants = `true` 기능이 동작했다고 볼 수 있습니다.
;;
;; java.sql.Timestamp 가 리턴되었습니다. clojure 에서 java.sql.Timestamp 일 때
;; `#inst` tagged literal 을 인쇄해 줍니다.

(def inst-a
  (:timestamp_t/ts
   (next.jdbc/execute-one! db ["select * from timestamp_t"])))

(class inst-a)

;; java.sql.Timestamp 는 이렇게 pr 도 잘 지원하고 `clojure.core/read` 에서도 잘 지원합니다

(= inst-a (read-string (pr-str inst-a)))

;; # Take away
;;
;; mysql server, cli 만 고려했을 때도 time_zone 설정이 있습니다. mysql client 들을 사용할 때는 영향을
;; 끼칠 때가 많습니다. mysql-connector-j 를 사용할 때는 관련이 덜 있습니다.
;;
;; Instant 는 clojure 에서 pr, read 를 지원하는 몇 안 되는 value 입니다. 많이 사용합니다!
;;
;; next.jdbc 로 string, LocalDateTime, ZonedDateTime, Instant, java.sql.Timestamp 모두
;; 바로 parameter 로 넘길 수 있습니다. 하지만 Instant(== java.time.Instant == java.sql.Timestamp) 로
;; 넘기는 것이 덜 헷갈리니 Instant 로 넣으면 좋겠습니다. 잘 들어갑니다.
;;
;; 예전에 주빈님이 적어준 것처럼 `DATETIME` 필드를 쓰면서 JVM TimeZone 을 'Asia/Seoul' 로 사용하면
;; ECS 에 올라간 서버와 동작이 약간 다를 수도 있으니 TimeZone 을 'UTC' 로 바꿔서 쓰는 것이 혼란이 적겠습니다.
;;
;; MySQL 의 TIMESTAMP 는 저장할 때 Instant 로 저장하고 꺼낼 때 변환을 한다고 합니다. wire bytearray 수준에서
;; 어떤 값이 오가는지는 모르지만 mysql-connector-j 에서 preserveInstants=true 가 설정되면 (default 값)
;; JVM 상황이 어찌되었던 넣었던 값을 select 했을 때 다시 돌려받는다는 것이 보장됩니다.
;;
;; MySQL 의 DATETIME 은 그렇게 보장되지 않지만 JVM 의 TimeZone 을 "UTC" 로 설정해서 쓰면 TIMESTAMP 를
;; 사용하는 것과 거의 사용할 때의 효과가 비슷하다고 볼 수 있습니다. select 해 올 때 LocalDateTime 으로 나오는 것은
;; 아쉽습니다. 새 ADR 을 만드는 것은 아직 안을 못 만들었으니 고려하지 않습니다.

;; # QnA

;; ### mysql 에서 timezone offset 이 붙은 리터럴을 8.0.19 부터 지원하기 시작했다. 이 기능이 jdbc 의 prepared statement 와 setParameter 처리 후에 일어나는 변환에 영향이 있을까?
;;
;; 없다. 우리의 사용방법에서는 없다고 봐야 한다. timezone offset 이 붙은 literal 을 보내는 경우가 없는 것 같다.

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
