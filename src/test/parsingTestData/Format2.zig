const
//
foo
//
=
    //
    bar
//
;

test
//
{
    foo
    //
    =
        //
        bar
    //
    ;

    foo
    //
    +=
        //
        bar
    //
    ;

    foo
    //
    ,
    //
    bar
    //
    =
        //
        baz
    //
    ;

    foo
    //
    :
    //
    {
        //
    }
}

const foo = union(enum(u8)) {
    //
}
//
;

const foo =
    //
    union
    //
    (
    //
    enum
    //
    (
    //
    u8
    //
    )
    //
    )
    //
    {
        //
    }
//
;

const foo = error{};

const foo = error{Foo};

const foo = error{
    Foo,
};

const foo =
    //
    error
    //
    {
        //
        Foo
        //
        ,
        //
    }
//
;

const foo = .{
    //
};

const foo =
    //
    .
    //
    {
        //
    }
//
;

const foo = switch (bar) {
    //
};

const foo =
    //
    switch (
    //
    bar
    //
    )
    //
    {
        //
    }
//
;

test {
    if
    //
    (
    //
    a
    //
    )
    //
    |
    //
    b
    //
    |
        c
    else
    //
    |
    //
    d
    //
    |
        e;

    if
    //
    (
    //
    foo
    //
    )
    //
    |
    //
    bar
    //
    |
    //
    {
        //
    }
    //
    else
    //
    |
    //
    baz
    //
    |
    //
    {
        //
    }
}
