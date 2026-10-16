package com.github.piotrszybicki.independentintelijaiplugin.cleanup

/** Which comments a removal sweep takes. */
enum class CommentChoice {
    /** Every Javadoc-form comment. */
    JAVADOC,

    /** Only the empty Javadoc stubs -- see [JavadocComments.isBlank]. */
    EMPTY_JAVADOC,

    /** Every line comment. */
    LINE,

    /** Both forms at once. */
    JAVADOC_AND_LINE,
}
