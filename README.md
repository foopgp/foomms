# foomms [PGP]

**SMS and MMS that only the person you write to can read**: locked for the
key their phone number means, signed by yours with your security key, and
carried by the phone network as ordinary messages.

*[Lire en français](README.fr.md)*

It holds no key and does no cryptography. It asks
[foodjis](https://foodjis.foopgp.org/), which must be installed, through the
OpenPGP service interface that mail applications already speak: foodjis keeps
the contacts, locks the message, and opens one with the security key.

## Why

- **Everybody already has it.** A phone number is the one address every phone
  answers to. No account to open, no application the other person must have
  joined, no server of ours between the two: the operators carry the message,
  as they carry any MMS, and see who writes to whom and how much — not what.
- **The number names the key.** A message *to a number* is locked *for the
  certificate that number means*, with nothing to choose and nothing to get
  wrong. Few people put their mobile number in their certificate, so the tie
  is kept where numbers already live: in the address book, each person
  writing for themselves whose certificate a number means.
- **A coded message makes one ask.** What travels is text, in armor. Somebody
  who receives it without the application sees a message that is plainly
  coded — and people copy and paste well enough to open it elsewhere.
- **The secret stays in the security key.** What the phone holds is public.
  Losing the phone, or having it read, gives nobody the means to read the
  messages again.

## How it works, today

It looks like a messaging application: the conversations the phone holds,
then one of them in bubbles, a field at the bottom.

- **Four colours.** Each message wears the colour of what it is worth: red,
  neither encrypted nor certified; yellow, encrypted and not certified; blue,
  certified and not encrypted; green, both. Received, *certified* means signed
  by somebody you certified; sent, that it went to somebody you certified. Not
  signed is not certified. An ordinary SMS is red.
- **A text stays a text.** A PGP message leaves in armor: as SMS while it
  takes five of them or fewer, as one MMS beyond, in both as text. Whoever
  receives it without this application sees that it is coded, and can copy it
  into foodjis.
- **How the next message leaves** is said by the button beside the field, in
  the colour it will have: ✍⚿ signed and encrypted, ⚿ encrypted, ✍ signed, ✉
  as it is. With a certificate tied to the number, signed and encrypted is how
  it starts.
- **Reading.** A received PGP message says what it is until it is pressed:
  foodjis opens it, with the security key if it is encrypted — and with it,
  for that one presentation of the key, the messages around it: five before
  and three after unless you set another number, from one to ten, in the
  configuration. A message still locked says whom it is for, by a small face
  for each key it names. One that does not open — locked for somebody else —
  can be forwarded or saved as it is. Pressed long, a message lets its text
  be selected and copied: the armor itself, for one still closed. Its words are
  held while the application runs and written nowhere; its colour is
  remembered.
- **The address book says whose key a number means.** *Tie a certificate to
  this number* shows foodjis's own list of contacts, over the conversation:
  a line to filter by, then each contact as a face and a name on the colour
  of what their certificate is worth. foodjis makes the vCard it
  already shares for that certificate; what it says is shown, a line each,
  and written in the contact once you agree: the address of the certificate
  on a key server as a web address — the form a vCard's KEY field gives it —
  and the name, addresses, numbers, notes and face, where the contact does
  not say them already. Any web address of a contact that holds a
  fingerprint is read as one. Opening a signed message from a number that
  means no certificate yet offers to tie it to the signer's. With no
  certificate tied, foodjis is asked for the certificate that carries the
  number itself.
- **The look of foodjis**: its colours and glyphs, and beside a name the
  contact's picture, or the face their certificate carries.

**The phone's messaging application**, when you make it so: it receives SMS
and MMS, writes them in the phone's own store as they travelled — a PGP
message in its armor —, and announces them; a PGP message is announced for
what it is, its words are not on a locked screen. Until then the phone's own
application receives, and this one shows and sends.

Permissions: sending and receiving messages and reading those the phone
holds; announcing them; reading the address book and writing in it. Not the network: the phone's own service
carries a message, and what is asked of a key server is asked through
foodjis.

## State

Version 0.0.1, a trial, tried on one phone against a trial build of foodjis.

- Done once, for real: a short text locked for a certificate, sent in armor as
  SMS (312 characters, three SMS), received on another phone and opened there
  by foodjis from a copy.
- Seen working: the conversation page and its button of four colours; foodjis
  asking whether to let the application in; a certificate tied to a made-up
  number, and the contact written with its address on a key server, its name,
  its note and its face.
- Tested off the phone: the MMS itself (`Pdu`, the M-Send.req of OMA-MMS-ENC
  1.2 in WSP's binary encoding), byte for byte; numbers; fingerprints; telling
  a PGP message, and a locked one from a signed one; vCard values.
- Done for real, as the phone's messaging application: an SMS sent and
  written, received, written and announced; a text too long for five SMS
  sent as an MMS, announced by the network, fetched, read and written, its
  text come back identical.
- Seen: a signed message checked from its bubble, in blue, and its number
  tied to the signer's certificate from the vCard foodjis makes.
- **Not seen yet**: an encrypted message opened from its bubble, and several
  opened by one presentation of the key — the phone it is tried on has no
  key that speaks over NFC; an MMS
  with an attachment; an MMS between two operators.
- **Not built yet**: attachments, shown or sent; several people in one
  conversation; deleting a message; a second SIM.

It needs a foodjis with four additions (branch `api-by-phone` of
foodjis-android, not in a published version): a phone number taken for an
address; its list of contacts shown to choose from; a door that answers, for
one fingerprint, the certificate's name, standing, face and vCard; and one
presentation of the key answering for every message the same application
waits to read.

## Building

A JDK 17 or newer and the Android SDK (platform 36).

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug     # org.foopgp.foomms.dev, which speaks to foodjis-dev
./gradlew :app:assembleRelease   # org.foopgp.foomms, which speaks to foodjis
```

## Leads and open questions

- **What operators do to an attachment.** Some rewrite pictures to fit the
  receiving phone, and may drop or relabel a part of a type they do not know.
  That is why a message is recognised by its bytes; if `application/pgp-encrypted`
  does not get through somewhere, the same bytes can travel under another
  label, or in armor as a text part. To be tried operator by operator, and
  between two of them.
- **More than a text.** What is locked is the text alone for now. With a
  photo or a voice note, the locked content should be a MIME entity, as in
  encrypted mail (RFC 3156): one attachment, whatever it holds, and any mail
  tool able to read it. Pictures are to be shrunk before they are locked —
  a network cannot do it afterwards.
- **Several people at once.** An MMS goes to several numbers, and a PGP
  message is locked for several keys: a group needs no more than that, and no
  group server.
- **Reading back what one sent.** A message is locked for its recipient only.
  Locking it for oneself as well would let the sender reopen it — from the
  phone's store if the phone kept it, which it does not for an application
  that is not the messaging application.
- **Being the messaging application.** Android lets one application receive
  MMS, keep them and announce them; the others read what it kept. Being that
  one would mean: a notification when a PGP message arrives, sent messages
  kept (locked), and the choice of what is written to the phone's store. It
  also means carrying every ordinary SMS and MMS properly, which is a large
  piece of work — and it is what an application store requires of one that
  asks for these permissions.
- **Sending one's certificate.** To somebody who has foodjis and not yet your
  certificate: the same MMS, with the certificate as its attachment.
- **Telling who can receive.** foodjis knows which contacts carry a number;
  a page listing them would spare typing the number, and say before writing
  whether the message can be locked.
- **A number that changes hands.** Operators reassign numbers. A certificate
  whose holder gave up a number should revoke that user id; until it does, a
  message locked for it is still readable only by the key's holder — but it
  reaches the number's new holder, who cannot read it.

## Licence

Copyright 2026 Jean-Jacques Brucker (u4sRyUhEbNU5OwyLEjfSwaXAe_42.17-002.76)
&lt;jjbrucker@foopgp.org&gt;\
Copyright 2026 Mnêmê (u5001777236237.945e_43.30_005.38) &lt;mneme@foopgp.org&gt;

**GNU General Public License, version 3 only (GPL-3.0-only)**; the full text
is in [`LICENSE`](LICENSE). The files of the OpenPGP service interface keep
their own licence: see [`THIRD-PARTY.md`](THIRD-PARTY.md).
