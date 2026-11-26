// Package waengine is the WhatsApp engine of the app. It is a small gomobile API over whatsmeow:
// link by phone code, list chats, and send sound stickers.
//
// Build with ./build.sh (gomobile bind). Kotlin sees it as the class waengine.Engine.
package waengine

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
)

// Listener gets engine events. Kotlin implements it. Calls come on a background thread.
type Listener interface {
	// OnState: state is one of "connecting", "unlinked", "pairing", "linked", "disconnected", "loggedout".
	OnState(state string, detail string)
}

// Engine is one WhatsApp linked device.
type Engine struct {
	mu       sync.Mutex
	listener Listener
	client   *whatsmeow.Client
	qrSeen   chan struct{}
}

// New opens (or creates) the session database in dataDir.
func New(dataDir string, listener Listener) (*Engine, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_CHROME.Enum()
	store.SetOSInfo("Audio Sticker Maker", [3]uint32{0, 1, 0})

	db, err := sql.Open("sqlite", "file:"+dataDir+"/session.db?_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := container.Upgrade(ctx); err != nil {
		return nil, fmt.Errorf("session db: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return nil, err
	}
	e := &Engine{listener: listener, client: whatsmeow.NewClient(device, waLog.Noop)}
	e.client.EnableAutoReconnect = true
	e.client.AddEventHandler(e.onEvent)
	return e, nil
}

func (e *Engine) emit(state, detail string) {
	if e.listener != nil {
		e.listener.OnState(state, detail)
	}
}

func (e *Engine) onEvent(evt any) {
	switch v := evt.(type) {
	case *events.QR:
		e.mu.Lock()
		if e.qrSeen != nil {
			close(e.qrSeen)
			e.qrSeen = nil
		}
		e.mu.Unlock()
	case *events.PairSuccess:
		e.emit("linked", v.ID.User)
	case *events.Connected:
		if e.client.Store.ID != nil {
			e.emit("linked", e.client.Store.ID.User)
		}
	case *events.Disconnected:
		e.emit("disconnected", "")
	case *events.LoggedOut:
		e.emit("loggedout", v.Reason.String())
	case *events.PairError:
		e.emit("unlinked", "Pairing failed: "+v.Error.Error())
	}
}

// IsLinked is true when this device is already linked to a WhatsApp account.
func (e *Engine) IsLinked() bool { return e.client.Store.ID != nil }

// Connect connects a linked device. For an unlinked device use PairPhone.
func (e *Engine) Connect() error {
	if !e.IsLinked() {
		e.emit("unlinked", "")
		return nil
	}
	if e.client.IsConnected() {
		e.emit("linked", e.client.Store.ID.User)
		return nil
	}
	e.emit("connecting", "")
	return e.client.Connect()
}

// PairPhone starts linking with a phone number (international format, digits only, e.g. 919876543210)
// and returns the 8-character code. WhatsApp also shows a notification on the phone.
func (e *Engine) PairPhone(phone string) (string, error) {
	phone = strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phone)
	if len(phone) < 8 {
		return "", errors.New("enter the full phone number with country code")
	}
	if e.IsLinked() {
		return "", errors.New("already linked")
	}
	e.client.Disconnect()
	seen := make(chan struct{})
	e.mu.Lock()
	e.qrSeen = seen
	e.mu.Unlock()
	e.emit("connecting", "")
	if err := e.client.Connect(); err != nil {
		return "", err
	}
	select {
	case <-seen:
	case <-time.After(20 * time.Second):
		return "", errors.New("WhatsApp did not answer. Check the internet connection")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	code, err := e.client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return "", err
	}
	e.emit("pairing", code)
	return code, nil
}

// Logout unlinks this device.
func (e *Engine) Logout() error {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := e.client.Logout(ctx)
	e.emit("loggedout", "")
	return err
}

// Disconnect closes the connection (the link stays).
func (e *Engine) Disconnect() { e.client.Disconnect() }

type chat struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Group bool   `json:"group"`
}

// Chats returns JSON: [{id,title,group}], groups first, then contacts, sorted by name.
func (e *Engine) Chats() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var out []chat
	if groups, err := e.client.GetJoinedGroups(ctx); err == nil {
		for _, g := range groups {
			out = append(out, chat{ID: g.JID.String(), Title: g.Name, Group: true})
		}
	}
	if contacts, err := e.client.Store.Contacts.GetAllContacts(ctx); err == nil {
		for jid, c := range contacts {
			if jid.Server != types.DefaultUserServer {
				continue
			}
			name := firstNonEmpty(c.FullName, c.FirstName, c.BusinessName, c.PushName)
			if name == "" {
				continue
			}
			out = append(out, chat{ID: jid.String(), Title: name})
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Group != out[j].Group {
			return out[i].Group
		}
		return strings.ToLower(out[i].Title) < strings.ToLower(out[j].Title)
	})
	b, err := json.Marshal(out)
	return string(b), err
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

// SendSoundSticker uploads the WebP sticker and its Ogg/Opus sound and sends them as one sound sticker.
// chatID "self" means your own chat ("Message yourself"). Returns the message ID.
//
// Message layout (same as WhatsApp clients):
//
//	Message.audioStickerMessage(134) -> FutureProofMessage.message -> Message.stickerMessage
//	  { ...sticker media..., isAnimated, premium=0, emojis, audioMessage{...ogg media..., ptt=false, seconds} }
func (e *Engine) SendSoundSticker(chatID, webpPath, oggPath string, seconds int, animated bool, emojis string) (string, error) {
	if !e.IsLinked() {
		return "", errors.New("WhatsApp is not linked")
	}
	if !e.client.IsConnected() {
		if err := e.client.Connect(); err != nil {
			return "", err
		}
		time.Sleep(2 * time.Second)
	}
	var to types.JID
	if chatID == "self" {
		to = e.client.Store.ID.ToNonAD()
	} else {
		j, err := types.ParseJID(chatID)
		if err != nil {
			return "", err
		}
		to = j
	}
	webp, err := os.ReadFile(webpPath)
	if err != nil {
		return "", err
	}
	ogg, err := os.ReadFile(oggPath)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	upAudio, err := e.client.Upload(ctx, ogg, whatsmeow.MediaAudio)
	if err != nil {
		return "", fmt.Errorf("audio upload: %w", err)
	}
	upSticker, err := e.client.Upload(ctx, webp, whatsmeow.MediaImage)
	if err != nil {
		return "", fmt.Errorf("sticker upload: %w", err)
	}
	if emojis == "" {
		emojis = "🔊"
	}
	sticker := &waE2E.StickerMessage{
		URL:           proto.String(upSticker.URL),
		DirectPath:    proto.String(upSticker.DirectPath),
		MediaKey:      upSticker.MediaKey,
		FileEncSHA256: upSticker.FileEncSHA256,
		FileSHA256:    upSticker.FileSHA256,
		FileLength:    proto.Uint64(upSticker.FileLength),
		Mimetype:      proto.String("image/webp"),
		Width:         proto.Uint32(512),
		Height:        proto.Uint32(512),
		IsAnimated:    proto.Bool(animated),
		StickerSentTS: proto.Int64(time.Now().UnixMilli()),
		Premium:       proto.Int32(0),
		Emojis:        proto.String(emojis),
		Audio: &waE2E.StickerMessage_AudioMessage{AudioMessage: &waE2E.AudioMessage{
			URL:               proto.String(upAudio.URL),
			DirectPath:        proto.String(upAudio.DirectPath),
			MediaKey:          upAudio.MediaKey,
			FileEncSHA256:     upAudio.FileEncSHA256,
			FileSHA256:        upAudio.FileSHA256,
			FileLength:        proto.Uint64(upAudio.FileLength),
			Mimetype:          proto.String("audio/ogg; codecs=opus"),
			Seconds:           proto.Uint32(uint32(max(1, seconds))),
			PTT:               proto.Bool(false),
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
		}},
	}
	msg := &waE2E.Message{AudioStickerMessage: &waE2E.FutureProofMessage{
		Message: &waE2E.Message{StickerMessage: sticker},
	}}
	resp, err := e.client.SendMessage(ctx, to, msg)
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}
