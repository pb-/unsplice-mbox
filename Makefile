develop:
	clojure -M:nrepl
.PHONY: develop

test:
	clojure -X:test
.PHONY: test
