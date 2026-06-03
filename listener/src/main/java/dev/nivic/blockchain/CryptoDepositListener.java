package dev.nivic.blockchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Listen to blockchain for crypto deposits.
 * Watches: Ethereum, Polygon, Optimism (any EVM-compatible RPC).
 * Detects: ERC20 transfers to custody wallets.
 * Emits: CRYPTO_DEPOSIT_INITIATED (pending) + CRYPTO_DEPOSIT_CONFIRMED (finalized).
 */
@Service
public class CryptoDepositListener {

  private static final Logger log = LoggerFactory.getLogger(CryptoDepositListener.class);

  @Value("${blockchain.rpc-url:https://eth-mainnet.g.alchemy.com/v2/demo}")
  private String rpcUrl;

  @Value("${blockchain.usdt-contract:0xdac17f958d2ee523a2206206994597c13d831ec7}")
  private String usdtContract;

  @Value("${blockchain.usdc-contract:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48}")
  private String usdcContract;

  @Value("${blockchain.custody-wallet:0x1234567890123456789012345678901234567890}")
  private String custodyWallet;

  @Value("${blockchain.poll-interval-seconds:12}")
  private long pollIntervalSeconds;

  private Web3j web3j;
  private BigInteger lastScannedBlock = null;
  private final Set<String> processedTxs = Collections.synchronizedSet(new HashSet<>());

  private final DepositEventPublisher eventPublisher;
  private final FxRateOracle fxOracle;

  public CryptoDepositListener(DepositEventPublisher eventPublisher, FxRateOracle fxOracle) {
    this.eventPublisher = eventPublisher;
    this.fxOracle = fxOracle;
  }

  public void init() {
    this.web3j = Web3j.build(new HttpService(rpcUrl));
    log.info("Blockchain listener initialized: {} → {}", rpcUrl, custodyWallet);
  }

  @Scheduled(fixedDelayString = "${blockchain.poll-interval-seconds:12}000")
  public void scanForDeposits() {
    try {
      BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();

      if (lastScannedBlock == null) {
        lastScannedBlock = currentBlock.subtract(BigInteger.valueOf(100)); // Start 100 blocks back
        log.info("Starting scan from block: {}", lastScannedBlock);
        return;
      }

      if (currentBlock.compareTo(lastScannedBlock) <= 0) {
        return; // No new blocks
      }

      log.debug("Scanning blocks {} → {}", lastScannedBlock, currentBlock);

      for (BigInteger blockNum = lastScannedBlock; blockNum.compareTo(currentBlock) <= 0; blockNum = blockNum.add(BigInteger.ONE)) {
        scanBlock(blockNum);
      }

      lastScannedBlock = currentBlock;
    } catch (Exception e) {
      log.error("Scan error", e);
    }
  }

  private void scanBlock(BigInteger blockNum) {
    try {
      EthBlock.Block block = web3j.ethGetBlockByNumber(
          DefaultBlockParameter.valueOf(blockNum),
          true  // Full transaction objects
      ).send().getBlock();

      if (block == null) return;

      for (EthBlock.TransactionResult<?> txResult : block.getTransactions()) {
        Transaction tx = (Transaction) txResult.get();
        checkTransaction(tx, block.getNumber(), block.getTimestamp().longValue());
      }
    } catch (Exception e) {
      log.error("Block scan error: {}", blockNum, e);
    }
  }

  private void checkTransaction(Transaction tx, BigInteger blockHeight, long blockTimestampMs) {
    String txHash = tx.getHash();

    // Skip if already processed
    if (processedTxs.contains(txHash)) {
      return;
    }

    // Check if this TX is a transfer to our custody wallet
    // (Simplified: check TO address and decode data)
    String toAddress = tx.getTo();
    if (toAddress == null) return;

    toAddress = toAddress.toLowerCase();
    String custodyLower = custodyWallet.toLowerCase();

    // ERC20 Transfer(address,address,uint256) has signature 0xa9059cbb
    // We're looking for transfers TO our wallet (recipient param)
    String input = tx.getInput();
    if (!input.startsWith("0xa9059cbb")) {
      return;
    }

    // Decode the transfer parameters
    try {
      // input format: 0xa9059cbb + 64-char address + 64-char amount
      if (input.length() < 138) return;

      String recipientAddr = "0x" + input.substring(34, 74);
      String amountHex = input.substring(74, 138);
      BigInteger amount = new BigInteger(amountHex, 16);

      // Check if recipient is our custody wallet
      if (!recipientAddr.toLowerCase().equals(custodyLower)) {
        return;
      }

      // Determine token type
      String tokenContract = tx.getTo().toLowerCase();
      String tokenType = identifyToken(tokenContract);
      if (tokenType == null) return;

      log.info("Deposit detected: {} {} from {} in tx {}", amount, tokenType, tx.getFrom(), txHash);

      // Fetch current confirmations and FX rate
      BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();
      int confirmations = currentBlock.subtract(blockHeight).intValue();

      BigDecimal fxRate = fxOracle.getRate(tokenType, "VND");

      // Emit event
      eventPublisher.publishDepositInitiated(
          "dep-" + txHash.substring(2, 10),
          tokenType,
          amount.longValue(),
          txHash,
          blockHeight.longValue(),
          tx.getFrom(),
          custodyWallet,
          confirmations,
          fxRate.longValue()
      );

      processedTxs.add(txHash);
    } catch (Exception e) {
      log.debug("Failed to decode transaction {}: {}", txHash, e.getMessage());
    }
  }

  private String identifyToken(String contractAddress) {
    String lower = contractAddress.toLowerCase();
    if (lower.equals(usdtContract.toLowerCase())) return "USDT";
    if (lower.equals(usdcContract.toLowerCase())) return "USDC";
    return null;
  }
}
