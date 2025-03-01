/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.mailinglist;

import org.openjdk.skara.email.*;

import java.io.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Mbox {
    private final static Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");

    private final static Pattern mboxMessagePattern = Pattern.compile(
            "^(From (?:.(?!^\\R^From ))*)", Pattern.MULTILINE | Pattern.DOTALL);
    private final static DateTimeFormatter ctimeFormat = DateTimeFormatter.ofPattern(
            "EEE LLL dd HH:mm:ss yyyy", Locale.US);
    private final static Pattern fromStringEncodePattern = Pattern.compile("^(>*From )", Pattern.MULTILINE);
    private final static Pattern fromStringDecodePattern = Pattern.compile("^>(>*From )", Pattern.MULTILINE);

    public static List<Email> splitMbox(String mbox, EmailAddress sender) {
        // Initial split
        var messages = mboxMessagePattern.matcher(mbox).results()
                                         .map(match -> match.group(1))
                                         .filter(message -> message.length() > 0)
                                         .map(Mbox::decodeFromStrings)
                                         .collect(Collectors.toList());

        // Pipermail can occasionally fail to encode 'From ' in message bodies, try to handle this
        var messageBuilder = new StringBuilder();
        var parsedMails = new ArrayList<Email>();
        Collections.reverse(messages);
        for (var message : messages) {
            messageBuilder.insert(0, message);
            try {
                var email = Email.from(Email.parse(messageBuilder.toString()));
                if (sender != null) {
                    email.sender(sender);
                }
                parsedMails.add(email.build());
                messageBuilder.setLength(0);
            } catch (RuntimeException ignored) {
            }
        }

        Collections.reverse(parsedMails);
        return parsedMails;
    }

    private static String encodeFromStrings(String body) {
        var fromStringMatcher = fromStringEncodePattern.matcher(body);
        return fromStringMatcher.replaceAll(">$1");
    }

    private static String decodeFromStrings(String body) {
        var fromStringMatcher = fromStringDecodePattern.matcher(body);
        return fromStringMatcher.replaceAll("$1");
    }

    private static final Pattern inReplyToPattern = Pattern.compile("<(.*@.*?)>");

    public static List<Conversation> parseMbox(List<Email> emails) {
        var idToMail = emails.stream().collect(Collectors.toMap(Email::id, Function.identity(), (a, b) -> a));
        var idToConversation = idToMail.values().stream()
                                       .filter(email -> !email.hasHeader("In-Reply-To"))
                                       .collect(Collectors.toMap(Email::id, Conversation::new));

        var unhandledEmails = emails;
        var lastUnhandledCount = 0;
        while (unhandledEmails.size() != lastUnhandledCount) {
            lastUnhandledCount = unhandledEmails.size();
            var emailsToCheck = unhandledEmails;
            unhandledEmails = new ArrayList<>();

            for (var email : emailsToCheck) {
                if (!idToConversation.containsKey(email.id())) {
                    EmailAddress inReplyTo = findInReplyTo(idToMail, email);
                    if (inReplyTo != null) {
                        if (!idToConversation.containsKey(inReplyTo)) {
                            unhandledEmails.add(email);
                        } else {
                            var conversation = idToConversation.get(inReplyTo);
                            var parent = idToMail.get(inReplyTo);
                            conversation.addReply(parent, email);
                            idToConversation.put(email.id(), conversation);
                        }
                    }
                }
            }
        }
        if (!unhandledEmails.isEmpty()) {
            log.info("Out of order remaining: " + unhandledEmails.size());
            unhandledEmails.forEach(oo -> log.info("  " + oo.id()));
        }

        return idToConversation.values().stream()
                               .distinct()
                               .collect(Collectors.toList());
    }

    private static EmailAddress findInReplyTo(Map<EmailAddress, Email> idToMail, Email email) {
        if (email.hasHeader("In-Reply-To")) {
            var inReplyToMatcher = inReplyToPattern.matcher(email.headerValue("In-Reply-To"));
            if (!inReplyToMatcher.find()) {
                log.info("Cannot parse In-Reply-To header: " + email.headerValue("In-Reply-To"));
            } else {
                var inReplyTo = EmailAddress.from(inReplyToMatcher.group(1));
                if (idToMail.containsKey(inReplyTo)) {
                    return inReplyTo;
                }
            }
        }
        if (email.hasHeader("References")) {
            var references = email.headerValue("References");
            var referenceList = Arrays.asList(references.split("\\s+"));
            Collections.reverse(referenceList);
            for (String reference : referenceList) {
                var referenceMatcher = inReplyToPattern.matcher(reference);
                if (referenceMatcher.find()) {
                    var referenceAddress = EmailAddress.from(referenceMatcher.group(1));
                    if (idToMail.containsKey(referenceAddress)) {
                        return referenceAddress;
                    }
                }
            }
        }
        log.info("Can't find parent for: " + email.id() + " - discarding");
        return null;
    }

    public static String fromMail(Email mail) {
        var mboxString = new StringWriter();
        var mboxMail = new PrintWriter(mboxString);

        mboxMail.println();
        mboxMail.println("From " + mail.sender().address() + "  " + mail.date().format(ctimeFormat));
        mboxMail.println("From: " + MimeText.encode(mail.author().toObfuscatedString()));
        if (!mail.author().equals(mail.sender())) {
            mboxMail.println("Sender: " + MimeText.encode(mail.sender().toObfuscatedString()));
        }
        if (!mail.recipients().isEmpty()) {
            mboxMail.println("To: " + mail.recipients().stream()
                                          .map(EmailAddress::toString)
                                          .map(MimeText::encode)
                                          .collect(Collectors.joining(", ")));
        }
        mboxMail.println("Date: " + mail.date().format(DateTimeFormatter.RFC_1123_DATE_TIME));
        mboxMail.println("Subject: " + MimeText.encode(mail.subject()));
        mboxMail.println("Message-Id: " + mail.id());
        mail.headers().forEach(header -> mboxMail.println(header + ": " + MimeText.encode(mail.headerValue(header))));
        mboxMail.println();
        mboxMail.println(encodeFromStrings(mail.body()));

        return mboxString.toString();
    }
}
