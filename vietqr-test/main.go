package main

import (
	"fmt"
	"image/png"
	"os"
	"strconv"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

func tlv(id, value string) string {
	return fmt.Sprintf("%s%02d%s", id, len(value), value)
}

func buildVietQR(bankBin, accountNo string, amount int64, note, holder string) string {
	mai := tlv("00", "A000000727") +
		tlv("01", "QRIBFTTA") +
		tlv("02", bankBin) +
		tlv("03", accountNo)

	var sb strings.Builder
	sb.WriteString(tlv("00", "01"))
	sb.WriteString(tlv("01", "12"))
	sb.WriteString(tlv("38", mai))
	sb.WriteString(tlv("52", "5999"))
	sb.WriteString(tlv("53", "704"))
	if amount > 0 {
		sb.WriteString(tlv("54", strconv.FormatInt(amount, 10)))
	}
	sb.WriteString(tlv("58", "VN"))
	if holder != "" {
		h := holder
		if len(h) > 25 {
			h = h[:25]
		}
		sb.WriteString(tlv("59", h))
	}
	sb.WriteString(tlv("60", "Viet Nam"))
	if note != "" {
		n := note
		if len(n) > 25 {
			n = n[:25]
		}
		sb.WriteString(tlv("62", tlv("08", n)))
	}
	sb.WriteString("6304")

	crc := crc16([]byte(sb.String()))
	sb.WriteString(fmt.Sprintf("%04X", crc))
	return sb.String()
}

func crc16(data []byte) uint16 {
	var crc uint16 = 0xFFFF
	for _, b := range data {
		crc ^= uint16(b) << 8
		for i := 0; i < 8; i++ {
			if crc&0x8000 != 0 {
				crc = (crc << 1) ^ 0x1021
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

var banks = map[string]string{
	"970436": "Vietcombank",
	"970415": "VietinBank",
	"970418": "BIDV",
	"970407": "Techcombank",
	"970422": "MB Bank",
	"970403": "Sacombank",
	"970432": "VPBank",
	"970416": "ACB",
	"970423": "TPBank",
	"970448": "OCB",
	"970441": "VIB",
	"970454": "Agribank",
}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "Usage: vietqr <bank_bin> <account_no> [amount] [note] [holder]")
		fmt.Fprintln(os.Stderr, "\nCommon bank BINs:")
		for bin, name := range banks {
			fmt.Fprintf(os.Stderr, "  %s  %s\n", bin, name)
		}
		fmt.Fprintln(os.Stderr, "\nExample:")
		fmt.Fprintln(os.Stderr, `  vietqr 970436 0123456789 150000 "thanh toan xe" "NGUYEN VAN A"`)
		os.Exit(1)
	}

	bankBin   := os.Args[1]
	accountNo := os.Args[2]
	var amount int64
	note   := ""
	holder := ""

	if len(os.Args) > 3 {
		amount, _ = strconv.ParseInt(os.Args[3], 10, 64)
	}
	if len(os.Args) > 4 {
		note = os.Args[4]
	}
	if len(os.Args) > 5 {
		holder = os.Args[5]
	}

	bankName := banks[bankBin]
	if bankName == "" {
		bankName = "Unknown bank"
	}

	qrStr := buildVietQR(bankBin, accountNo, amount, note, holder)

	fmt.Println("─────────────────────────────────────────────────────────────────────────")
	fmt.Printf("Bank:    %s (%s)\n", bankName, bankBin)
	fmt.Printf("Account: %s\n", accountNo)
	if holder != "" {
		fmt.Printf("Holder:  %s\n", holder)
	}
	if amount > 0 {
		fmt.Printf("Amount:  %d ₫\n", amount)
	}
	if note != "" {
		fmt.Printf("Note:    %s\n", note)
	}
	fmt.Println("─────────────────────────────────────────────────────────────────────────")
	fmt.Println(qrStr)
	fmt.Println("─────────────────────────────────────────────────────────────────────────")

	outFile := "vietqr.png"
	qr, err := qrcode.New(qrStr, qrcode.High)
	if err != nil {
		fmt.Fprintf(os.Stderr, "qrcode error: %v\n", err)
		os.Exit(1)
	}

	img := qr.Image(400)
	f, err := os.Create(outFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "create: %v\n", err)
		os.Exit(1)
	}
	defer f.Close()

	if err := png.Encode(f, img); err != nil {
		fmt.Fprintf(os.Stderr, "encode: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("\nQR saved → %s/%s\n", mustPwd(), outFile)
	fmt.Println("Mở file PNG và quét bằng app ngân hàng bất kỳ.")
}

func mustPwd() string {
	d, _ := os.Getwd()
	return d
}
