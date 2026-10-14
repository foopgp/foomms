# foomms [PGP]

**Des SMS et MMS que seule la personne à qui vous écrivez peut lire** :
chiffrés pour la clé que désigne son numéro de téléphone, signés par la vôtre
avec votre clé de sécurité, et transportés par le réseau téléphonique comme
des messages ordinaires.

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
- **Le numéro désigne la clé.** Un message *pour un numéro* est chiffré *pour
  le certificat que ce numéro désigne*, sans rien à choisir ni à confondre.
  Peu de gens mettent leur numéro de mobile dans leur certificat : le lien se
  garde donc là où vivent déjà les numéros, dans le carnet d'adresses, chacun
  y inscrivant pour soi quel certificat un numéro désigne.
- **Un message codé intrigue.** Ce qui voyage est du texte, sous armure. Qui
  le reçoit sans l'application voit un message manifestement codé — et les
  gens savent assez bien copier-coller pour l'ouvrir ailleurs.
- **Le secret reste dans la clé de sécurité.** Ce que le téléphone détient
  est public. Perdre le téléphone, ou le voir fouillé, ne donne à personne de
  quoi relire les messages.

## Comment ça marche, aujourd'hui

Cela ressemble à une application de messagerie : les conversations que le
téléphone détient, puis l'une d'elles en bulles, un champ en bas.

- **Quatre couleurs.** Chaque message porte la couleur de ce qu'il vaut :
  rouge, ni chiffré ni certifié ; jaune, chiffré et non certifié ; bleu,
  certifié et non chiffré ; vert, les deux. *Certifié* veut dire signé — par
  quelqu'un que vous avez certifié, pour un message reçu ; par vous, vers
  quelqu'un que vous avez certifié, pour un message envoyé. Non signé, c'est non certifié. Un SMS
  ordinaire est rouge ; un message PGP pas encore ouvert est gris, puisque
  ce qu'il vaut — signé ou non — ne se sait qu'une fois la clé passée.
- **Un texte reste un texte.** Un message part en SMS tant qu'il en faut
  cinq ou moins, en un MMS au-delà — ou dès qu'il dépasse un SMS, si vous
  réglez de privilégier les MMS ; et vous pouvez demander à être prévenu
  avant qu'un MMS parte, qu'un opérateur peut facturer. Dans les deux cas
  c'est du texte, un message PGP sous son armure.
  Qui le reçoit sans cette application voit qu'il est codé, et peut le copier
  dans foodjis.
- **Une photo, une image, un son, un contact, un fichier** part en MMS :
  pris, choisi ou enregistré par **+**, qui écrit aussi dans le message où
  l'on est (une adresse `geo:` et le même lieu sur OpenStreetMap) ; une
  image étant réduite avant le départ pour tenir dans ce que prend le réseau.
  Chiffré ou signé, le fichier devient un message PGP à lui, sous son nom
  suivi de `.pgp`, que foodjis ouvre comme n'importe quel fichier ; le texte
  reste un texte sous armure à côté, et signer les deux ne demande la clé de
  sécurité qu'une fois. Reçu chiffré, ce que porte un message s'ouvre avec son texte par le même
  passage de la clé, et s'affiche comme s'il était arrivé en clair. Reçue en
  clair, une image s'affiche en
  petit et un son se joue sur place.
- **Relire ce qu'on a envoyé** : un message chiffré l'est aussi pour vous,
  sauf si vous réglez que non.
- **Appuyer pour parler** : le champ, tant qu'il est vide, maintenu enregistre
  quelques mots aussi longtemps qu'on le tient — AMR, basse qualité, que tout
  téléphone lit —, joints au prochain message comme n'importe quel son.
- **Comment part le prochain message** se lit sur deux interrupteurs au-dessus
  du champ, *Signer* et *Chiffrer*, chacun grisé quand il ne peut pas servir ;
  la ligne qui les porte, et le bouton d'envoi, prennent la couleur qu'aura le
  message. Avec un certificat relié au numéro, les deux sont allumés d'emblée.
- **Un même message pour plusieurs personnes** : nommées l'une après l'autre
  au moment de commencer un message, chacune le reçoit dans sa propre
  conversation. Chiffré, c'est un seul message PGP pour toutes, comme une
  lettre à plusieurs destinataires — chacune peut voir, aux clés qu'il
  désigne, pour qui d'autre il était.
- **Lire.** Un message PGP reçu dit ce qu'il est jusqu'à ce qu'on appuie
  dessus : foodjis l'ouvre, avec la clé de sécurité s'il est chiffré — et avec
  lui, pour ce seul passage de la clé, les messages affichés avant et après lui : deux de chaque côté, sauf autre
  nombre, de zéro à quatre, réglé dans la configuration — PGP ou non, ils
  comptent. foodjis dit dans sa fenêtre chaque étape de ce qu'il fait. Un message encore chiffré dit pour qui il est, par un petit
  visage pour chaque clé qu'il désigne. Celui qui ne s'ouvre pas — chiffré
  pour quelqu'un d'autre — peut être transféré ou enregistré tel quel. Un
  appui sur un message ouvert ouvre ce qu'il porte, ou montre son texte à
  sélectionner. Par un appui long, il dit ce qu'on peut en faire : copier ou sélectionner
  son texte — l'armure elle-même, pour un message encore fermé —, ouvrir ce
  qu'il porte, le transférer, voir ses détails, le supprimer. Le texte d'un message
  ouvert est gardé en mémoire vive le temps que vous réglez — quinze minutes
  sauf autre choix — et n'est jamais écrit ; sa couleur part avec lui, et
  refermé il redevient gris.
- **Le carnet d'adresses dit quelle clé désigne un numéro.** *Relier un
  certificat à ce numéro* montre la liste de contacts de foodjis, par-dessus
  la conversation : une ligne pour filtrer, puis chaque contact en un visage
  et un nom sur la couleur de ce que vaut son certificat. foodjis
  produit la vCard qu'il partage déjà pour ce certificat ; ce qu'elle dit est
  montré, une ligne par donnée, et inscrit dans le contact une fois votre
  accord donné : l'adresse du certificat sur un serveur de clés comme adresse
  web — la forme que lui donne un champ KEY de vCard — et le nom, les
  adresses, numéros, notes et visage, là où le contact ne les dit pas déjà.
  Toute adresse web d'un contact qui contient une empreinte se lit comme
  telle. Ouvrir un message signé venu d'un numéro qui ne désigne encore aucun
  certificat propose de le relier à celui du signataire. Sans certificat
  relié, on demande à foodjis le certificat qui porte lui-même le numéro.
- **Une conversation, par un appui long**, ou son en-tête une fois ouverte,
  dit ce qu'on peut en faire : afficher ou ajouter le contact, relier le
  numéro à un certificat — ou, une fois relié, voir ce certificat dans
  foodjis, le relire, ou l'oublier —, rendre son expéditeur silencieux — ce que la liste
  montre ensuite —, la supprimer. Supprimer est réservé à l'application de
  messagerie du téléphone. L'en-tête d'une conversation ouverte montre le
  visage, le nom, et dessous le numéro et ce que vaut son certificat.
- **Dans la liste**, un numéro relié à un certificat porte la lettre que lui
  donne la page des contacts de foodjis, sur sa couleur : C, U, E, R, B.
- **Des compteurs**, dans la barre de la liste : combien de messages sont PGP
  parmi tous, et parmi les non lus. Un appui les détaille, et la liste ne
  garde que les conversations qui contiennent un message non lu.
- **Un nouveau message** commence par un numéro, ou quelques lettres d'un
  nom du carnet d'adresses.
- **Sauvegarder et restaurer**, depuis le menu : tous les messages, ou ceux
  de la conversation ouverte, enregistrés dans un fichier — ou remis depuis
  un fichier — dans le XML de « SMS Backup & Restore », que lisent la plupart
  des applications de messagerie. Les messages PGP sont enregistrés tels
  qu'ils ont voyagé : chiffrés.
- **L'allure de foodjis**, claire ou sombre comme le téléphone ou comme vous
  le réglez : ses couleurs et ses glyphes, et à côté d'un nom
  la photo du contact, ou le visage que porte son certificat.

**L'application de messagerie du téléphone**, quand vous en décidez : elle
reçoit SMS et MMS, les écrit dans la mémoire du téléphone tels qu'ils ont
voyagé — un message PGP sous son armure —, et les annonce ; un message PGP
est annoncé pour ce qu'il est, son texte n'est pas sur un écran verrouillé.
D'ici là c'est l'application du téléphone qui reçoit, et celle-ci montre et
envoie.

Permissions : envoyer et recevoir des messages et lire ceux que le téléphone
détient ; les annoncer ; lire le carnet d'adresses et y écrire. Pas le réseau : c'est le service du
téléphone qui transporte un message, et ce qui se demande à un serveur de
clés se demande par foodjis.

## État

Version 0.0.1, d'essai, essayée sur un téléphone face à une version d'essai
de foodjis.

- Fait une fois, pour de vrai : un texte court chiffré pour un certificat,
  envoyé sous armure par SMS (312 caractères, trois SMS), reçu sur un autre
  téléphone et ouvert là par foodjis depuis une copie.
- Vu fonctionner : la page de conversation et son bouton aux quatre
  couleurs ; foodjis qui demande s'il faut laisser entrer l'application ; un
  certificat relié à un numéro inventé, et le contact écrit avec son adresse
  sur un serveur de clés, son nom, sa note et son visage.
- Testé hors du téléphone : le MMS lui-même (`Pdu`, le M-Send.req
  d'OMA-MMS-ENC 1.2 dans l'encodage binaire de WSP), octet par octet ; les
  numéros ; les empreintes ; reconnaître un message PGP, et un chiffré d'un
  signé ; les valeurs vCard.
- Fait pour de vrai, en tant qu'application de messagerie du téléphone : un
  SMS envoyé et écrit, reçu, écrit et annoncé ; un texte trop long pour cinq
  SMS envoyé en MMS, annoncé par le réseau, récupéré, lu et écrit, son texte
  revenu identique.
- Vu : un message signé vérifié depuis sa bulle, en bleu, et son numéro
  relié au certificat du signataire depuis la vCard que produit foodjis.
- **Pas encore vu** : un message chiffré ouvert depuis sa bulle, et plusieurs
  ouverts par un seul passage de la clé — le téléphone d'essai n'a pas de clé
  qui parle en NFC ; un MMS avec pièce jointe ; un MMS entre deux opérateurs.
- **Pas encore vu non plus** : une image ou un son choisi, envoyé et reçu —
  construit, et essayé seulement jusqu'au choix entre les deux.
- **Pas encore construit** : ouvrir une pièce jointe chiffrée depuis sa
  bulle ; plusieurs personnes dans une conversation ; supprimer un message ; une
  seconde carte SIM.

Il faut un foodjis avec quatre ajouts (branche `api-by-phone` de
foodjis-android, dans aucune version publiée) : un numéro de téléphone pris
pour une adresse ; sa liste de contacts montrée pour y choisir ; une porte qui
répond, pour une empreinte, le nom du certificat, son statut, son visage et
sa vCard ; et un seul passage de la clé qui répond pour tout ce que la même
application attend — des messages à lire, ou un texte et sa pièce jointe à
signer.

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
- **Être l'application de messagerie.** Android laisse une seule application
  recevoir les MMS, les garder et les annoncer ; les autres lisent ce qu'elle
  a gardé. Être celle-là voudrait dire : une notification à l'arrivée d'un
  message PGP, les messages envoyés conservés (chiffrés), et le choix de ce
  qui s'écrit dans la mémoire du téléphone. Cela veut dire aussi porter
  correctement tous les SMS et MMS ordinaires, ce qui est un gros travail — et
  c'est ce qu'un magasin d'applications exige de qui demande ces permissions.
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
