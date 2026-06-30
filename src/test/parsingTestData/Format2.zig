const
//
foo
//
=
    //
    bar
//
;

const Foo = struct {
    bar
    //
    :
    //
    Bar
    //
    =
        //
        baz,
};

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

    for
    //
    (
        //
        a
        //
        ,
        //
        b
        //
        ..
        //
        c
        //
        ,
        //
    )
    //
    |
        //
        *
        //
        d
        //
        ,
        //
        e
        //
        ,
        //
    |
    //
    {}

    (
        //
        foo
        //
    )
    //
    ;

    foo
    //
    ()
    //
    ;

    @foo
    //
    ()
    //
    ;
}

const Foo = fn (
    //
) Bar;

const Foo =
    //
    fn
    //
    (
        //
    )
    //
    Bar
//
;

fn
//
foo
//
(
    //
)
//
Foo
//
{}

const Foo =
    //
    [
    //
    :
    //
    sentinel
    //
    ]
    //
    Bar
//
;

const Foo =
    //
    [
    //
    *
    //
    :
    //
    sentinel
    //
    ]
    //
    Bar
//
;

const Foo =
    //
    [
        //
        length
        //
        :
        //
        sentinel
        //
    ]
    //
    Bar
//
;

const foo =
    //
    bar
    //
    [
        //
        index
        //
    ]
//
;

const foo =
    //
    bar
    //
    [
    //
    start
    //
    ..
    //
    end
    //
    :
    //
    sentinel
    //
    ]
//
;

const foo = bar catch |baz|
    qux;

const foo =
    //
    bar
    //
    catch
        //
        |
        //
        baz
        //
        |
        //
        qux
//
;

const foo = a
    //
    and
    //
    b
    //
    and
    //
    c
//
;

const foo =
    //
    bar
        //
        .
        //
        baz
//
;
