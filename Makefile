GRADLE := ./gradlew
JAVA_HOME_SDKMAN := $(SDKMAN_DIR)/candidates/java/21.0.10-tem
export JAVA_HOME ?= $(JAVA_HOME_SDKMAN)

.PHONY: build test run clean coverage fat-jar check help

## Build the project
build:
	$(GRADLE) build -x test

## Run all tests
test:
	$(GRADLE) test

## Run the CLI (pass ARGS="..." for arguments)
run:
	$(GRADLE) run --args='$(ARGS)'

## Clean build artifacts
clean:
	$(GRADLE) clean

## Run tests with coverage report (HTML at build/reports/jacoco/test/html/index.html)
coverage:
	$(GRADLE) test jacocoTestReport

## Build fat JAR with all dependencies
fat-jar:
	$(GRADLE) fatJar

## Run build + tests + coverage verification
check:
	$(GRADLE) check

## Show this help
help:
	@echo "Available targets:"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /' | paste - <(grep -E '^[a-z][-a-z]*:' $(MAKEFILE_LIST) | sed 's/:.*//') | awk '{printf "  %-12s %s\n", $$NF, $$0}' || true
	@echo ""
	@echo "  build        Build the project"
	@echo "  test         Run all tests"
	@echo "  run          Run the CLI (ARGS=\"--list\")"
	@echo "  clean        Clean build artifacts"
	@echo "  coverage     Run tests + generate coverage report"
	@echo "  fat-jar      Build fat JAR with all dependencies"
	@echo "  check        Full build + tests + coverage verification"
	@echo "  help         Show this help"
