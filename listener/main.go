package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"math/big"
	"os"
	"os/signal"
	"strings"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/ethclient"
	amqp "github.com/rabbitmq/amqp091-go"
)

// Config from environment
type Config struct {
	RPCUrl          string
	RabbitMQURL     string
	Exchange        string
	RoutingKey      string
	USDTContract    string
	USDCContract    string
	CustodyWallet   string
	PollInterval    time.Duration
	Confirmations   int
	StartBlock      uint64
}

// CryptoDepositEvent matches the ledger event schema
type CryptoDepositEvent struct {
	EventID      int64                  `json:"event_id"`
	EventType    string                 `json:"event_type"`
	Timestamp    int64                  `json:"timestamp"`
	Source       string                 `json:"source"`
	CorrelationID int64                  `json:"correlation_id"`
	Data         map[string]interface{} `json:"data"`
	RetryCount   int                    `json:"retry_count"`
}

// Listener watches blockchain for deposits
type Listener struct {
	client          *ethclient.Client
	rabbit          *amqp.Channel
	config          Config
	lastScannedBlock uint64
	processedTxs    map[string]bool
	mu              sync.RWMutex
	tokenContracts  map[string]string // contract address -> token name
}

func main() {
	cfg := loadConfig()
	log.Printf("Starting blockchain listener: %s → %s", cfg.RPCUrl, cfg.CustodyWallet)

	// Connect to Ethereum
	client, err := ethclient.Dial(cfg.RPCUrl)
	if err != nil {
		log.Fatalf("Failed to connect to RPC: %v", err)
	}
	defer client.Close()

	// Connect to RabbitMQ
	conn, err := amqp.Dial(cfg.RabbitMQURL)
	if err != nil {
		log.Fatalf("Failed to connect to RabbitMQ: %v", err)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("Failed to open channel: %v", err)
	}
	defer ch.Close()

	// Declare exchange & queue
	err = ch.ExchangeDeclare(cfg.Exchange, "topic", true, false, false, false, nil)
	if err != nil {
		log.Fatalf("Failed to declare exchange: %v", err)
	}

	listener := &Listener{
		client:          client,
		rabbit:          ch,
		config:          cfg,
		lastScannedBlock: cfg.StartBlock,
		processedTxs:    make(map[string]bool),
		tokenContracts: map[string]string{
			strings.ToLower(cfg.USDTContract): "USDT",
			strings.ToLower(cfg.USDCContract): "USDC",
		},
	}

	// Graceful shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt)

	// Main poll loop
	ticker := time.NewTicker(cfg.PollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			listener.scanForDeposits()
		case <-sigChan:
			log.Println("Shutting down...")
			return
		}
	}
}

func (l *Listener) scanForDeposits() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Get current block
	header, err := l.client.HeaderByNumber(ctx, nil)
	if err != nil {
		log.Printf("Error getting current block: %v", err)
		return
	}

	currentBlock := header.Number.Uint64()
	if l.lastScannedBlock == 0 {
		l.lastScannedBlock = currentBlock - 100
		log.Printf("Starting scan from block %d", l.lastScannedBlock)
		return
	}

	if currentBlock <= l.lastScannedBlock {
		return
	}

	log.Printf("Scanning blocks %d → %d", l.lastScannedBlock, currentBlock)

	// Scan each block
	for blockNum := l.lastScannedBlock + 1; blockNum <= currentBlock; blockNum++ {
		l.scanBlock(blockNum)
	}

	l.lastScannedBlock = currentBlock
}

func (l *Listener) scanBlock(blockNum uint64) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	block, err := l.client.BlockByNumber(ctx, big.NewInt(int64(blockNum)))
	if err != nil {
		log.Printf("Error fetching block %d: %v", blockNum, err)
		return
	}

	custodyLower := strings.ToLower(l.config.CustodyWallet)

	for _, tx := range block.Transactions() {
		if tx.To() == nil {
			continue // Contract creation, skip
		}

		toAddr := strings.ToLower(tx.To().Hex())

		// Check if TO is a token contract (ERC20 Transfer method)
		if tokenName, isToken := l.tokenContracts[toAddr]; isToken {
			l.checkERC20Transfer(tx, block, blockNum, tokenName)
		}
	}

	// Check for ETH transfers to custody wallet
	for _, tx := range block.Transactions() {
		if tx.To() != nil && strings.ToLower(tx.To().Hex()) == custodyLower {
			if tx.Value().Sign() > 0 {
				l.publishETHDeposit(tx, blockNum, block.Time())
			}
		}
	}
}

func (l *Listener) checkERC20Transfer(tx *ethclient.Transaction, block *ethclient.Block, blockNum uint64, tokenName string) {
	// ERC20 Transfer signature: 0xa9059cbb (transfer(address,address,uint256))
	input := tx.Data()
	if len(input) < 68 || hex.EncodeToString(input[:4]) != "a9059cbb" {
		return
	}

	// Decode recipient (32 bytes after selector)
	recipientBytes := input[4:36]
	recipient := common.BytesToAddress(recipientBytes).Hex()

	// Decode amount (next 32 bytes)
	amountBytes := input[36:68]
	amount := new(big.Int).SetBytes(amountBytes)

	custodyLower := strings.ToLower(l.config.CustodyWallet)
	if strings.ToLower(recipient) != custodyLower {
		return // Not to our wallet
	}

	l.mu.Lock()
	if l.processedTxs[tx.Hash().Hex()] {
		l.mu.Unlock()
		return // Already processed
	}
	l.processedTxs[tx.Hash().Hex()] = true
	l.mu.Unlock()

	log.Printf("ERC20 Deposit detected: %s %s from %s", amount.String(), tokenName, tx.From().Hex())

	// Fetch current confirmations
	currentBlock := big.NewInt(int64(blockNum))
	blockHeight := new(big.Int).SetUint64(blockNum)

	confirmations := new(big.Int).Sub(currentBlock, blockHeight).Int64()
	if confirmations < 0 {
		confirmations = 0
	}

	// Get FX rate (stub: hardcoded for now)
	fxRate := int64(24500) // 1 USDT = 24,500 VND

	// Publish event
	l.publishDepositInitiated(
		"dep-"+tx.Hash().Hex()[2:10],
		tokenName,
		amount.Int64(),
		tx.Hash().Hex(),
		blockNum,
		tx.From().Hex(),
		l.config.CustodyWallet,
		int(confirmations),
		fxRate,
	)
}

func (l *Listener) publishETHDeposit(tx *ethclient.Transaction, blockNum uint64, blockTime uint64) {
	l.mu.Lock()
	if l.processedTxs[tx.Hash().Hex()] {
		l.mu.Unlock()
		return
	}
	l.processedTxs[tx.Hash().Hex()] = true
	l.mu.Unlock()

	log.Printf("ETH Deposit detected: %s from %s", tx.Value().String(), tx.From().Hex())

	currentBlock := big.NewInt(int64(blockNum))
	blockHeight := new(big.Int).SetUint64(blockNum)
	confirmations := new(big.Int).Sub(currentBlock, blockHeight).Int64()

	l.publishDepositInitiated(
		"dep-"+tx.Hash().Hex()[2:10],
		"ETH",
		tx.Value().Int64(),
		tx.Hash().Hex(),
		blockNum,
		tx.From().Hex(),
		l.config.CustodyWallet,
		int(confirmations),
		2400, // 1 ETH = 2,400 USD stub
	)
}

func (l *Listener) publishDepositInitiated(
	depositID, currency string,
	amount int64,
	txHash string,
	blockHeight uint64,
	fromAddr, toAddr string,
	confirmations int,
	fxRate int64,
) {
	event := CryptoDepositEvent{
		EventID:      time.Now().UnixNano(),
		EventType:    "CRYPTO_DEPOSIT_INITIATED",
		Timestamp:    time.Now().UnixMilli(),
		Source:       "blockchain-listener",
		CorrelationID: hashCorrelation(depositID),
		Data: map[string]interface{}{
			"deposit_id":            depositID,
			"crypto_currency":       currency,
			"crypto_amount":         amount,
			"tx_hash":               txHash,
			"block_height":          blockHeight,
			"from_address":          fromAddr,
			"to_address":            toAddr,
			"block_confirmations":   confirmations,
			"exchange_rate_snapshot": fxRate,
		},
		RetryCount: 0,
	}

	payload, _ := json.Marshal(event)
	l.rabbit.Publish(l.config.Exchange, l.config.RoutingKey, false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        payload,
	})

	log.Printf("Published CRYPTO_DEPOSIT_INITIATED: %s %s", depositID, currency)
}

func hashCorrelation(depositID string) int64 {
	h := sha256.Sum256([]byte(depositID))
	var result int64
	for i := 0; i < 8; i++ {
		result = (result << 8) | int64(h[i])
	}
	return result
}

func loadConfig() Config {
	return Config{
		RPCUrl:        getEnv("BLOCKCHAIN_RPC_URL", "https://eth-mainnet.g.alchemy.com/v2/demo"),
		RabbitMQURL:   getEnv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/"),
		Exchange:      getEnv("RABBITMQ_EXCHANGE", "gtel-events"),
		RoutingKey:    getEnv("RABBITMQ_ROUTING_KEY", "ledger.crypto"),
		USDTContract:  getEnv("USDT_CONTRACT", "0xdac17f958d2ee523a2206206994597c13d831ec7"),
		USDCContract:  getEnv("USDC_CONTRACT", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
		CustodyWallet: getEnv("CUSTODY_WALLET", "0x1234567890123456789012345678901234567890"),
		PollInterval:  12 * time.Second,
		Confirmations: 12,
		StartBlock:    0,
	}
}

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}
