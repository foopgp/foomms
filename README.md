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
  certified and not encrypted; green, both. *Certified* means signed — by
  somebody you certified, for a message received; by you, to somebody you
  certified, for one sent. Not
  signed is not certified. An ordinary SMS is red.
- **A text stays a text.** A message leaves as SMS while it takes five of
  them or fewer, as one MMS beyond — or beyond a single SMS, if you set MMS
  to be preferred; and you can ask to be told before an MMS leaves, which an
  operator may charge for. In both it is text, a PGP message in its armor. Whoever
  receives it without this application sees that it is coded, and can copy it
  into foodjis.
- **A photo, a picture, a sound, a contact, a file** goes as an MMS: taken,
  chosen or recorded with **+**, which also writes where one is into the
  message (a `geo:` address and the same place on OpenStreetMap); a
  picture made small enough for the network before it leaves. Locked or
  signed, the file becomes a PGP message of its own, under its name and
  `.pgp`, which foodjis opens as it opens any file; the words stay a text in
  armor beside it, and signing both asks for the security key once. Received in clear, a picture is shown small and a sound
  plays where it stands.
- **Read again what you sent**: a locked message is locked for you too,
  unless you set it not to be.
- **How the next message leaves** is said by two switches above the field,
  *Sign* and *Encrypt*, each grey when it cannot be; the line they stand on,
  and the button that sends, take the colour the message will have. With a
  certificate tied to the number, both are on to begin with.
- **One message for several people**: named one after the other when a new
  message is begun, each receives it in their own conversation. Encrypted,
  it is one PGP message for all of them, as a letter with several addressees
  is — each can see, by the keys it names, who else it was for.
- **Reading.** A received PGP message says what it is until it is pressed:
  foodjis opens it, with the security key if it is encrypted — and with it,
  for that one use of the key, the closed messages nearest to it: two of them
  unless you set another number, from none to five, in the configuration. A
  message still locked says whom it is for, by a small face for each key it
  names. One that does not open — locked for somebody else — can be forwarded
  or saved as it is. Pressed once it is open, a message opens what it carries, or shows its
  text to select from. Pressed long, it says what can be done with it:
  copy or select its text — the armor itself, for one still closed —, open
  what it carries, forward it, see its details, delete it. The words of
  an opened message are held in memory for the time you set — fifteen minutes
  unless said otherwise — and never written down; its colour is remembered.
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
- **A conversation pressed long**, or its head pressed once it is open, says
  what can be done about it: show or add the contact, tie the number to a
  certificate — or, once it is, see that certificate in foodjis, read it
  again, or forget it —, silence its sender — which the list then shows —, delete it.
  Deleting is for the phone's messaging application. The head of an open
  conversation shows the face, the name, and under it the number and what
  its certificate is worth.
- **On the list**, a number tied to a certificate carries the letter the
  contacts page of foodjis gives it, on its colour: C, U, E, R, B.
- **Counts**, in the bar of the list: how many messages are PGP among all,
  and among those unread. Pressed, they are spelled out, and the list keeps
  only the conversations that hold something unread.
- **A new message** begins with a number, or a few letters of a name in the
  address book.
- **The look of foodjis**, light or dark as the phone is or as you set it:
  its colours and glyphs, and beside a name the
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
- **Not seen yet either**: a picture or a sound chosen, sent and received —
  built, and tried only as far as the choice between the two.
- **Not built yet**: opening a locked attachment from its bubble; several
  people in one conversation; deleting a message; a second SIM.

It needs a foodjis with four additions (branch `api-by-phone` of
foodjis-android, not in a published version): a phone number taken for an
address; its list of contacts shown to choose from; a door that answers, for
one fingerprint, the certificate's name, standing, face and vCard; and one
presentation of the key answering for everything the same application waits
for — messages to read, or a text and what it carries to sign.

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
