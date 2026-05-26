.PHONY: ci build test clean

# ─── Build checks (no services needed) ───────────────────────────────────────

build:
	@echo "── Wire (C) ────────────────────────────────────────────────────────"
	$(MAKE) -C saving
	@echo "── Merchants (Go) ──────────────────────────────────────────────────"
	cd Merchants && go build ./...
	cd Merchants && go vet ./...
	@echo "── saving-ios (Swift) ──────────────────────────────────────────────"
	cd saving-ios && swift build 2>&1 | tail -3
	@echo ""
	@echo "✓  all builds passed"

# ─── Integration tests (spins up Wire + Postgres in Docker) ──────────────────

ci: build
	@echo ""
	@echo "── Starting CI containers ──────────────────────────────────────────"
	docker compose -f docker-compose.ci.yml down -v --remove-orphans 2>/dev/null || true
	docker compose -f docker-compose.ci.yml build wire
	docker compose -f docker-compose.ci.yml up -d
	@echo "── Waiting for Wire on :17474 ──────────────────────────────────────"
	@timeout 30 bash -c 'until nc -z 127.0.0.1 17474 2>/dev/null; do sleep 1; done' \
		|| (docker compose -f docker-compose.ci.yml logs wire; $(MAKE) ci-clean; exit 1)
	@echo "── Seeding Bank account ────────────────────────────────────────────"
	@sleep 1
	python3 saving/tests/seed_bank.py --port 17474
	@echo ""
	@echo "── Running Wire test suite ─────────────────────────────────────────"
	WIRE_HOST=127.0.0.1 WIRE_PORT=17474 bash saving/tests/run_all.sh; \
		STATUS=$$?; $(MAKE) ci-clean; exit $$STATUS

ci-clean:
	docker compose -f docker-compose.ci.yml down -v --remove-orphans 2>/dev/null || true

# ─── Merchants HTTP smoke tests (requires local Merchants running) ─────────────

test-merchants:
	@echo "── Merchants vet + build ───────────────────────────────────────────"
	cd Merchants && go vet ./... && go build ./...
	@echo "✓  Merchants OK"

clean:
	rm -f saving/saving Merchants/merchants-linux
	$(MAKE) ci-clean
