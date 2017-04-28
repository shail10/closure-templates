/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.Identifier.Type;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.internalutils.NodeContentKinds;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;

/** A name-attribute pair (e.g. {@code <name>="<attribute>"}) as parsed from a soy command. */
public final class CommandTagAttribute {

  private static final SoyErrorKind DUPLICATE_ATTRIBUTE =
      SoyErrorKind.of("Attribute ''{0}'' was already specified.");
  private static final SoyErrorKind INVALID_ATTRIBUTE =
      SoyErrorKind.of("Invalid value for attribute ''{0}'', expected {1}.");
  private static final SoyErrorKind INVALID_ATTRIBUTE_LIST =
      SoyErrorKind.of("Invalid value for attribute ''{0}'', expected one of {1}.");
  private static final SoyErrorKind INVALID_CSS_BASE_NAMESPACE_NAME =
      SoyErrorKind.of("Invalid CSS base namespace name ''{0}''.");
  private static final SoyErrorKind INVALID_REQUIRE_CSS_ATTRIBUTE =
      SoyErrorKind.of("Invalid required CSS namespace name ''{0}'', expected an identifier.");
  static final SoyErrorKind UNSUPPORTED_ATTRIBUTE_KEY =
      SoyErrorKind.of("Unsupported attribute ''{0}'' for ''{1}'' tag, expected one of {2}.");
  public static final SoyErrorKind UNSUPPORTED_ATTRIBUTE_KEY_SINGLE =
      SoyErrorKind.of("Unsupported attribute ''{0}'' for ''{1}'' tag, expected ''{2}''.");

  private static final String TO_STRING_FORMAT = "%s=\"%s\"";

  /**
   * Identifies duplicate attributes, reports an error for each one, and removes them from the
   * {@link Iterable}.
   */
  public static void removeDuplicatesAndReportErrors(
      Iterable<CommandTagAttribute> attrs, ErrorReporter errorReporter) {
    Set<String> seenAttributes = new HashSet<>();
    for (Iterator<CommandTagAttribute> iterator = attrs.iterator(); iterator.hasNext(); ) {
      CommandTagAttribute attr = iterator.next();
      Identifier name = attr.getName();
      if (!seenAttributes.add(name.identifier())) {
        errorReporter.report(name.location(), DUPLICATE_ATTRIBUTE, name.identifier());
        iterator.remove();
      }
    }
  }

  private final Identifier key;
  // either value or valueExpr must be set, but not both.
  @Nullable private final String value;
  @Nullable private final ExprNode valueExpr;
  private final SourceLocation valueLocation;

  public CommandTagAttribute(Identifier key, String value, SourceLocation valueLocation) {
    checkArgument(key.type() == Type.SINGLE_IDENT, "expected a single identifier, got: %s", key);
    this.key = checkNotNull(key);
    this.value = checkNotNull(value);
    this.valueExpr = null;
    this.valueLocation = checkNotNull(valueLocation);
  }

  public CommandTagAttribute(Identifier key, ExprNode valueExpr) {
    checkArgument(key.type() == Type.SINGLE_IDENT, "expected a single identifier, got: %s", key);
    this.key = checkNotNull(key);
    this.value = null;
    this.valueExpr = checkNotNull(valueExpr);
    this.valueLocation = valueExpr.getSourceLocation();
  }

  /** Returns the name. It is guaranteed to be a single identifier. */
  public Identifier getName() {
    return key;
  }

  /** Returns the string value. Do not call on an expression attribute. */
  public String getValue() {
    return checkNotNull(value);
  }

  public SourceLocation getValueLocation() {
    return valueLocation;
  }

  boolean valueAsBoolean(ErrorReporter errorReporter, boolean defaultValue) {
    checkState(valueExpr == null);

    if ("true".equals(value)) {
      return true;
    } else if ("false".equals(value)) {
      return false;
    } else {
      errorReporter.report(
          valueLocation,
          INVALID_ATTRIBUTE_LIST,
          key.identifier(),
          ImmutableList.of("true", "false"));
      return defaultValue;
    }
  }

  public int valueAsInteger(ErrorReporter errorReporter, int defaultValue) {
    checkState(valueExpr == null);

    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      errorReporter.report(valueLocation, INVALID_ATTRIBUTE, key.identifier(), "an integer");
      return defaultValue;
    }
  }

  TriState valueAsTriState(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    if ("true".equals(value)) {
      return TriState.ENABLED;
    } else if ("false".equals(value)) {
      return TriState.DISABLED;
    } else {
      errorReporter.report(
          valueLocation,
          INVALID_ATTRIBUTE_LIST,
          key.identifier(),
          ImmutableList.of("true", "false"));
      return TriState.UNSET;
    }
  }

  ImmutableList<String> valueAsRequireCss(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    String[] namespaces = value.trim().split("\\s*,\\s*");
    boolean hasError = false;
    for (String namespace : namespaces) {
      if (!BaseUtils.isDottedIdentifier(namespace)) {
        errorReporter.report(valueLocation, INVALID_REQUIRE_CSS_ATTRIBUTE, namespace);
        hasError = true;
      }
    }
    return hasError ? ImmutableList.<String>of() : ImmutableList.copyOf(namespaces);
  }

  AutoescapeMode valueAsAutoescapeMode(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    AutoescapeMode mode = AutoescapeMode.forAttributeValue(value);
    if (mode == null) {
      mode = AutoescapeMode.STRICT; // default for unparsed
      errorReporter.report(
          valueLocation,
          INVALID_ATTRIBUTE_LIST,
          key.identifier(),
          ImmutableList.copyOf(AutoescapeMode.getAttributeValues()));
    }
    return mode;
  }

  @Nullable
  Visibility valueAsVisibility(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    Visibility visibility = Visibility.forAttributeValue(value);
    if (visibility == null) {
      errorReporter.report(
          valueLocation,
          INVALID_ATTRIBUTE_LIST,
          key.identifier(),
          ImmutableList.copyOf(Visibility.getAttributeValues()));
    }
    return visibility;
  }

  @Nullable
  public ContentKind valueAsContentKind(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    ContentKind contentKind = NodeContentKinds.forAttributeValue(value);
    if (contentKind == null) {
      errorReporter.report(
          valueLocation,
          INVALID_ATTRIBUTE_LIST,
          key.identifier(),
          ImmutableList.copyOf(NodeContentKinds.getAttributeValues()));
    }
    return contentKind;
  }

  String valueAsCssBase(ErrorReporter errorReporter) {
    checkState(valueExpr == null);

    if (!BaseUtils.isDottedIdentifier(value)) {
      errorReporter.report(valueLocation, INVALID_CSS_BASE_NAMESPACE_NAME, value);
    }
    return value;
  }

  /** Returns the value as an expression. Only call on an expression attribute. */
  public ExprNode valueAsExpr() {
    checkState(value == null);
    return checkNotNull(valueExpr);
  }

  @Override
  public String toString() {
    String valueStr = (value != null) ? value.replace("\"", "\\\"") : valueExpr.toSourceString();
    return String.format(TO_STRING_FORMAT, key.identifier(), valueStr);
  }
}
