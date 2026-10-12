# foomms [PGP]

**Des messages par MMS que seule la personne à qui vous écrivez peut lire** :
chiffrés pour la clé que désigne son numéro de téléphone, signés par la vôtre
avec votre clé de sécurité, et transportés par le réseau téléphonique comme
un MMS ordinaire.

*[Read in English](README.md)*

L'application ne détient aucune clé et ne fait aucune cryptographie. Elle
demande à [foodjis](https://foodjis.foopgp.org/), qui doit être installé, par
l'interface de service OpenPGP que parlent déjà les applications de courriel :
foodjis tient les contacts, chiffre le message, et en ouvre un avec la clé de
sécurité.

## Pourquoi

- **Tout le monde l'a déjà.** Un numéro de téléphone est la seule adresse à
  laquelle tout téléphone répond. Pas de compte à ouvrir, pas d'application
  que l'autre doive avoir rejointe, pas de serveur à nous entre les deux : les
  opérateurs transportent le message, comme n'importe quel MMS, et voient qui
  écrit à qui et combien — pas quoi.
- **Le numéro désigne la clé.** Un certificat PGP ID porte le numéro de
  téléphone de son titulaire parmi ses identités, et celles et ceux qui ont
  certifié le titulaire s'en sont portés garants. Un message *pour un numéro*
  peut donc être chiffré *pour le certificat de ce numéro*, sans rien à
  choisir ni à confondre — et un message *venu d'un numéro* se confronte au
  certificat de qui l'a signé.
- **Le MMS, pas le SMS.** Un SMS fait 160 caractères ; un message PGP pour
  une personne en fait au moins trois cents, sous armure, et part en deux à
  dix SMS. Un MMS transporte des octets tels quels, trois cents kilo-octets
  sur n'importe quel réseau : un seul message, sans armure, et de la place
  pour une photo ou une note vocale.
- **Le secret reste dans la clé de sécurité.** Ce que le téléphone détient
  est public. Perdre le téléphone, ou le voir fouillé, ne donne à personne de
  quoi relire les messages.

## Comment ça marche, aujourd'hui

1. **✎ Écrire** : un numéro de téléphone et un texte.
2. **⚿ Chiffrer & envoyer**, ou **✍⚿ Signer, chiffrer & envoyer** : on
   demande à foodjis un message chiffré « pour `tel:+33…` ». Il répond avec le
   certificat qui porte ce numéro parmi vos contacts — celui que vous avez
   certifié si plusieurs le portent, aucun si rien ne les départage — et, pour
   une signature, demande votre clé de sécurité. Quand aucun contact ne porte
   le numéro, foodjis montre ses contacts, où chercher le certificat.
3. Ce qui part est un MMS en deux parties : une ligne en clair, *Message PGP
   chiffré : message.pgp*, et la pièce jointe `message.pgp`, un message PGP
   tel que le lit tout outil PGP (RFC 9580 ; sans armure). C'est le service
   MMS du téléphone qui l'envoie ; cette application n'a pas accès au réseau.
4. **Recevoir** est l'affaire de l'application de messagerie du téléphone,
   comme pour tout MMS. Celle-ci cherche, parmi les MMS que le téléphone
   détient, les parties qui sont des messages PGP — reconnues à leurs premiers
   octets, pas à leur étiquette — et en dresse la liste.
5. **⚷ Lire** : foodjis ouvre le message avec la clé de sécurité. Le texte
   s'affiche avec qui l'a signé, si vous l'avez certifié, et si le numéro
   d'où vient le message est de ceux que porte son certificat. Il n'est
   conservé nulle part.

Qui reçoit un tel message sans cette application voit la ligne en clair et
une pièce jointe, que foodjis sait ouvrir.

Deux permissions : envoyer des MMS, et lire les messages que le téléphone
détient — pour y trouver les messages PGP ; aucune autre partie n'est
ouverte. De ce qui part d'ici, l'application garde une ligne par envoi :
quand, pour quel numéro, quel poids, et si le réseau l'a pris. Jamais le
texte.

## État

Version 0.0.1, d'essai, essayée sur un téléphone face à une version d'essai
de foodjis.

- Vu fonctionner : les pages, et la question posée à foodjis — pour un numéro
  qu'aucun contact ne porte, foodjis montre ses contacts.
- Testé hors du téléphone : le MMS lui-même (`Pdu`, le M-Send.req
  d'OMA-MMS-ENC 1.2 dans l'encodage binaire de WSP), octet par octet ; dire si
  deux numéros n'en font qu'un ; reconnaître un message PGP à ses premiers
  octets.
- **Pas encore fait une seule fois : envoyer un vrai MMS, en recevoir un,
  l'ouvrir.** Rien ici ne dit qu'un opérateur transporte la pièce jointe sans
  y toucher.

Il faut un foodjis qui prenne un numéro de téléphone pour une adresse
(branche `api-by-phone` de foodjis-android, dans aucune version publiée).

## Construire

Un JDK 17 ou plus récent et le SDK Android (plateforme 36).

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug     # org.foopgp.foomms.dev, qui parle à foodjis-dev
./gradlew :app:assembleRelease   # org.foopgp.foomms, qui parle à foodjis
```

## Pistes et questions ouvertes

- **Ce que les opérateurs font d'une pièce jointe.** Certains retaillent les
  images pour le téléphone qui reçoit, et peuvent écarter ou réétiqueter une
  partie d'un type qu'ils ne connaissent pas. C'est pourquoi un message se
  reconnaît à ses octets ; si `application/pgp-encrypted` ne passe pas quelque
  part, les mêmes octets peuvent voyager sous une autre étiquette, ou sous
  armure dans une partie texte. À essayer opérateur par opérateur, et de l'un
  à l'autre.
- **Plus qu'un texte.** Seul le texte est chiffré pour l'instant. Avec une
  photo ou une note vocale, le contenu chiffré devrait être une entité MIME,
  comme dans le courriel chiffré (RFC 3156) : une seule pièce jointe, quoi
  qu'elle contienne, lisible par tout outil de courriel. Les images sont à
  réduire avant d'être chiffrées — un réseau ne peut plus le faire après.
- **Plusieurs personnes à la fois.** Un MMS part vers plusieurs numéros, et
  un message PGP se chiffre pour plusieurs clés : un groupe ne demande rien
  de plus, et pas de serveur de groupe.
- **Relire ce qu'on a envoyé.** Un message n'est chiffré que pour son
  destinataire. Le chiffrer aussi pour soi permettrait de le rouvrir — depuis
  la mémoire du téléphone s'il l'a gardé, ce qu'il ne fait pas pour une
  application qui n'est pas l'application de messagerie.
- **Être l'application de messagerie.** Android laisse une seule application
  recevoir les MMS, les garder et les annoncer ; les autres lisent ce qu'elle
  a gardé. Être celle-là voudrait dire : une notification à l'arrivée d'un
  message PGP, les messages envoyés conservés (chiffrés), et le choix de ce
  qui s'écrit dans la mémoire du téléphone. Cela veut dire aussi porter
  correctement tous les SMS et MMS ordinaires, ce qui est un gros travail — et
  c'est ce qu'un magasin d'applications exige de qui demande ces permissions.
- **Un seul geste de la clé pour plusieurs messages.** Ouvrir demande la clé
  de sécurité à chaque fois. Les messages non lus pourraient s'ouvrir
  ensemble, la clé posée une fois contre le téléphone.
- **Envoyer son certificat.** À qui a foodjis et pas encore votre
  certificat : le même MMS, avec le certificat en pièce jointe.
- **Dire qui peut recevoir.** foodjis sait quels contacts portent un numéro ;
  une page qui les liste épargnerait de taper le numéro, et dirait avant
  d'écrire si le message peut être chiffré.
- **Un numéro qui change de mains.** Les opérateurs réattribuent les numéros.
  Un certificat dont le titulaire a rendu un numéro devrait révoquer cette
  identité ; tant qu'il ne l'a pas fait, un message chiffré pour lui reste
  lisible par le seul détenteur de la clé — mais il arrive chez le nouveau
  titulaire du numéro, qui ne peut pas le lire.

## Licence

Copyright 2026 Jean-Jacques Brucker (u4sRyUhEbNU5OwyLEjfSwaXAe_42.17-002.76)
&lt;jjbrucker@foopgp.org&gt;\
Copyright 2026 Mnêmê (u5001777236237.945e_43.30_005.38) &lt;mneme@foopgp.org&gt;

**Licence publique générale GNU, version 3 seulement (GPL-3.0-only)** ; le
texte complet est dans [`LICENSE`](LICENSE). Les fichiers de l'interface de
service OpenPGP gardent leur propre licence : voir
[`THIRD-PARTY.md`](THIRD-PARTY.md).
