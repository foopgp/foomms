# foomms [PGP]

**Messages by MMS that only the person you write to can read**: locked for
the key their phone number means, signed by yours with your security key, and
carried by the phone network as an ordinary MMS.

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
- **The number names the key.** A PGP ID certificate carries its holder's
  phone number among its user ids, and the people who certified the holder
  vouched for it. So a message *to a number* can be locked *for that number's
  certificate*, with nothing to choose and nothing to get wrong — and a
  message *from a number* can be checked against the certificate of whoever
  signed it.
- **MMS, not SMS.** An SMS is 160 characters; a PGP message for one person is
  three hundred at least, in armor, and goes as two to ten SMS. An MMS carries
  bytes as they are, three hundred kilobytes of them on any network: one
  message, no armor, and room for a photo or a voice note.
- **The secret stays in the security key.** What the phone holds is public.
  Losing the phone, or having it read, gives nobody the means to read the
  messages again.

## How it works, today

1. **✎ Write**: a phone number and a text.
2. **⚿ Encrypt & send**, or **✍⚿ Sign, encrypt & send**: foodjis is asked for
   a message locked "for `tel:+33…`". It answers with the certificate that
   carries that number among your contacts — the one you certified if several
   do, and none if nothing tells them apart — and, for a signature, asks for
   your security key. When no contact carries the number, foodjis shows its
   contacts, where the certificate can be looked for.
3. What leaves is an MMS with two parts: a line in clear, *Encrypted PGP
   message: message.pgp*, and the attachment `message.pgp`, a PGP message as
   any PGP tool reads it (RFC 9580; not armored). The phone's own MMS service
   sends it; this application has no access to the network.
4. **Receiving** is the work of the phone's messaging application, as for any
   MMS. This one looks, among the MMS the phone holds, for parts that are PGP
   messages — told by their first bytes, not by their label — and lists them.
5. **⚷ Read**: foodjis opens the message with the security key. The text is
   shown with who signed it, whether you certified them, and whether the
   number the message came from is one their certificate carries. It is kept
   nowhere.

Somebody who receives such a message without this application sees the line
in clear and an attachment, which foodjis opens.

Two permissions: sending MMS, and reading the messages the phone holds —
used to find the PGP messages among them; no other part is opened. Of what is
sent from here, the application keeps a line each: when, to what number, how
heavy, and whether the network took it. Never the text.

## State

Version 0.0.1, a trial, tried on one phone against a trial build of foodjis.

- Seen working: the pages, and the question to foodjis — for a number no
  contact carries, foodjis shows its contacts.
- Tested off the phone: the MMS itself (`Pdu`, the M-Send.req of OMA-MMS-ENC
  1.2 in WSP's binary encoding), byte for byte; telling whether two numbers
  are one; telling a PGP message from its first bytes.
- Done once: a short text locked for a certificate named by its fingerprint,
  sent in armor as SMS (312 characters, three SMS).
- **Not yet done once: sending a real MMS, receiving a message, opening it.**
  Nothing here says an operator carries an attachment untouched.

It needs a foodjis that takes a phone number for an address (branch
`api-by-phone` of foodjis-android, not in a published version).

## Where it is going

Decided since this first application, and not yet built:

- **The phone's messaging application.** foomms is to receive, keep and
  announce every SMS and MMS, and to look much like the messaging
  applications people know: conversations, bubbles, a field at the bottom.
- **A text stays a text.** A written message travels in armor, as text: whoever
  receives it without the application sees that it is coded, which makes one
  ask, and can copy it. Bytes without armor are for attachments.
- **Four colours**, for messages received and sent: red, neither encrypted nor
  certified; yellow, encrypted and not certified; blue, certified and not
  encrypted; green, both. Not signed is not certified.
- **The look of foodjis**: its colours, its glyphs, the avatars of its
  contacts — which the service interface does not carry yet.
- **The address book says whose key a number means.** Few people put their
  mobile number in their certificate: each person ties a number to a
  certificate by writing the certificate's fingerprint in the contact, as a
  vCard KEY field does (`KEY;MEDIATYPE=application/pgp-keys:` and the address
  of the certificate on a key server).

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
- **One touch of the key for several messages.** Opening asks for the
  security key each time. Unread messages could be opened together, while
  the key is held against the phone once.
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
