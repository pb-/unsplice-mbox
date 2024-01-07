develop:
	clojure -M:nrepl
.PHONY: develop

test:
	clojure -X:test
.PHONY: test

release:
	clojure -T:build uber
	cat build/stub.sh target/unsplice-mbox.jar > target/unsplice-mbox
	chmod a+x target/unsplice-mbox
.PHONY: release
