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
  signed is not certified. An ordinary SMS is red; a PGP message
  not yet opened is grey, since what it is worth — signed or not — is known
  only once the key has opened it. Certified, but signed by somebody other
  than the person of the conversation, a received message is violet, or
  yellow-green if it is also encrypted: a forwarded message, for one.
- **A text stays a text.** A message leaves as SMS while it takes seven of
  them or fewer — from one to thirty-two, set in the configuration, which
  recalls what the operator says —, as one MMS beyond — or beyond a single SMS, if you set MMS
  to be preferred; and you can ask to be told before an MMS leaves, which an
  operator may charge for. In both it is text, a PGP message in its armor. Whoever
  receives it without this application sees that it is coded, and can copy it
  into foodjis.
- **A photo, a picture, a sound, a contact, a file** goes as an MMS: taken,
  chosen or recorded with **+**, which also writes where one is into the
  message (a `geo:` address and the same place on OpenStreetMap); a
  picture made small enough for the network before it leaves: 300 kB, or up
  to what the operator announces, if you set it so. Locked or
  signed, the file becomes a PGP message of its own, under its name and
  `.pgp`, which foodjis opens as it opens any file; the words stay a text in
  armor beside it, and signing both asks for the security key once. Received locked, what a message carries is opened with its words by the same
  use of the key, and shown as if it had come in clear; what you send
  locked is shown open in your bubble likewise. A picture is shown small, and
  opens on the whole screen to zoom, move and turn; a sound plays where it
  stands. An MMS that carries a locked or signed part asks the network not to
  adapt it (`X-Mms-Adaptation-Allowed: No`, OMA-MMS-ENC 1.3): cut down, the
  part would be lost. A network that refuses the whole MMS for it -- Free
  does -- is remembered, and the MMS leaves again at once without asking.
  Should an operator take a part out on the way all the same, the bubble says
  so, in the operator's own words.
- **Read again what you sent**: a locked message is locked for you too,
  unless you set it not to be.
- **Push to talk**: the field, while empty, held down records a few words
  for as long as it is held — AMR, low quality, which every phone plays, as
  a `.amr` and not wrapped in 3GP, which Android takes for a video —,
  attached to the next message like any sound.
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
  for that one use of the key, the messages shown before it and after it:
  seven on each side unless you set another number, from none to fifteen, in
  the configuration — PGP or not, they count —, and a few unread messages of
  the other conversations; foodjis says in its prompt each step
  of what it does. A message still locked says whom it is for, by a small face for each key it
  names. One that does not open — locked for somebody else — can be forwarded
  or saved as it is. Pressed once it is open, a message opens what it carries, or shows its
  text to select from. Pressed long, it says what can be done with it:
  copy or select its text — the armor itself, for one still closed —, open
  what it carries, forward it, see its details, delete it. The words of
  an opened message are held in memory for the time you set — fifteen minutes
  unless said otherwise — and never written down; its colour goes with them,
  and closed again it is grey again. A strip under the bar counts the
  messages open and the time left, in the conversation and on the list;
  several conversations open, it unrolls onto a line for each.
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
- **Groups**, conversations among several people as in any messaging
  application, of up to twenty members unless set otherwise. Each message
  carries a small vCard, `foomms-group.vcf`, naming the members: some
  operators rewrite the "To" of a group MMS, and the group is recognised by
  it whatever the way. A group can be named, on your phone only, and
  silenced: the list and the head of the conversation then say so, a group
  with no name too. A member's face in a bubble, or the member pressed long
  in the group's menu, offers to talk with them alone.
- **My profile**, the second page: the counts of messages and
  conversations; the drafts and the unread messages, each on its own line,
  opening onto the list of the conversations concerned; the backup; your
  "Me" card. The number of unread messages shows next to "My profile".
- **A new message** begins with a number, or a few letters of a name in the
  address book; a contact pressed there says what can be done about it.
- **Back up and restore**, from My profile for every message, or from a
  conversation's menu for its own: saved to a file — or put back from one — in the XML
  of "SMS Backup & Restore", which most messaging applications read. PGP
  messages are saved as they travelled: locked.
- **The look of foodjis**, light or dark as the phone is or as you set it:
  its colours and glyphs, and beside a name the
  contact's picture, or the face their certificate carries.

**The phone's messaging application**, when you make it so: it receives SMS
and MMS, writes them in the phone's own store as they travelled — a PGP
message in its armor —, and announces them; a PGP message is announced for
what it is, its words are not on a locked screen. An MMS the phone could
not download is said, and asked for again for seven days. Until then the phone's own
application receives, and this one shows and sends.

Permissions: sending and receiving messages and reading those the phone
holds; announcing them; reading the address book and writing in it; placing
the call one asks for from a conversation; in foomms+, reading the call log. Not the network: the phone's own service
carries a message, and what is asked of a key server is asked through
foodjis.

**foomms+**, an edition of its own (`org.foopgp.foomms.plus`), meant for
F-Droid: the calls with a person slip in among their messages, on the side
of whoever called, those that took place in the red of a message in clear,
missed and refused ones in grey; a switch in the settings takes them out.
Google Play does not let a messaging application read the call log, hence
an edition apart; the rest is the same.

## State

Version 1.4.2, for foomms+ on F-Droid. 1.3.3 is published on Google Play,
and 1.4.1 is under review there; each version of foomms can also be
downloaded from [foomms.foopgp.org](https://foomms.foopgp.org/), with its
fingerprint and its signature.

In use every day on two phones (Android 13 and 16), from one operator to
another: SMS and MMS, encrypted, signed or in clear, texts, photos, sounds,
videos and files, one-to-one conversations and groups.

It needs foodjis, as published on
[foodjis.foopgp.org](https://foodjis.foopgp.org/) and Google Play.

## Building

A JDK 17 or newer and the Android SDK (platform 36).

```sh
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:assembleStandardDebug    # org.foopgp.foomms.dev, which speaks to foodjis-dev
./gradlew :app:bundleStandardRelease    # org.foopgp.foomms, for Google Play
./gradlew :app:assemblePlusRelease      # org.foopgp.foomms.plus, foomms+ for F-Droid
```

## Leads and open questions

- **What operators do to an attachment.** Some rewrite pictures to fit the
  receiving phone, or take out a part too heavy and put a word in its place.
  The field `X-Mms-Adaptation-Allowed: No` (OMA-MMS-ENC 1.3) kept a 574 kB
  locked part whole from Orange to Free; sent through Free, an MMS carrying it
  is refused (HTTP 400). It remains to be tried with other operators.
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
